package hawk.cloud.coordinator;

import com.alibaba.fastjson.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 发起索引构建：给每个分片**随机挑一个在线副本**，把请求透传过去。
 * 方案见 {@code INDEX_BUILD_DISPATCH_DESIGN.md} §2。
 *
 * <h2>它是路由器，不是校验器</h2>
 * 协调器对字段 schema **零知识** —— `fieldList` / `vectorFields` 白名单的唯一权威是索引器的
 * {@code TextIndexController.parseFieldList}（那个方法跨模块、跨包都够不着），抄一份就是第三份拷贝。
 * 所以这里**不解析、不重建**请求体，原样转发。
 *
 * <h2>逐副本试，但 400 立刻中止整轮</h2>
 * 409 是**软**失败（节点忙），换下一个副本；400 是**请求体**的问题 —— 同分片的副本收到的是
 * 同一个请求体，换谁都会 400，继续试只会刷 N 条同样的日志、还把根因埋掉。
 *
 * <h2>先写轮次，再派发</h2>
 * 顺序不能反，理由见 {@link RoundStore#startNewRound}。
 */
@Service
public class IndexBuildDispatcher {

    private static final Logger log = LoggerFactory.getLogger(IndexBuildDispatcher.class);

    /** 倒排索引器。 */
    public static final String ROLE_TEXT = "text-indexer";
    /** 向量索引器。 */
    public static final String ROLE_VECTOR = "vector-indexer";

    /**
     * 建连超时。局域网、索引器在同网段，比索引器下载端点允许的等待更紧 ——
     * 这里的对端只做一次提交，不该等太久。
     */
    private static final int CONNECT_TIMEOUT_MS = 2_000;

    private final NodeRegistry nodeRegistry;
    private final RoundStore roundStore;
    private final ConvergenceChecker checker;

    /**
     * 每个 role 最近一次派发的**完整请求**（轮次 + 分片数 + 请求体）。
     *
     * <p>为什么要有它：{@link RoundTracker} 发现某一轮没收敛时要**重下发**，而重下发需要原本那个
     * 请求体 —— 协调器除了这次派发之外没有任何地方存过它。
     *
     * <p><b>内存态，协调器重启即丢。</b>丢了之后 tracker 只能告警（它重下发不出构建，
     * 索引器会 400），等次数耗尽后放弃、把闸门打开，由人工重新发起。这是刻意的取舍：
     * 把请求体也落进注册中心能换来自愈，但那要多存一份可能很大的 JSON、而且多一处可能与轮次
     * 失步的状态。写在设计文档 §7.9 里。
     */
    private final Map<String, Dispatched> lastDispatch = new ConcurrentHashMap<>();

    /**
     * 单次派发的等待上限。索引器只做「校验 + startAsync」，**立刻**回 202，所以 5 秒已经很宽。
     *
     * <p><b>别和推送索引那条链路混了</b>：推索引是真的在传 200 MB，给的是 10 分钟；
     * 索引器只做「校验 + startAsync」，立刻回 202 —— 这里等的是一个受理回执，不是一次传输。
     */
    @Value("${hawk.cloud.coordinator.build.timeout-ms:5000}")
    private long requestTimeoutMs;

    /**
     * 只覆盖"派发瞬间"的许可。它防的是两个调用方**同时**派发（两个循环交错、每个分片互相抢 202），
     * <b>不防</b>"上一轮还在建、这一轮又派" —— 那要几分钟，锁不可能占那么久；
     * 那种情况由**索引器自己那把 fullPermit** 兜住（第二次派发收到 409）。
     *
     * <p>用 {@code Semaphore} 而不是 {@code ReentrantLock}：与索引器侧保持一致，
     * 且 {@code tryAcquire} 的语义读起来更直白。
     */
    private final Semaphore dispatchPermit = new Semaphore(1);

    /** 连不上就报错、不重试的短超时客户端 —— 只用来丢派发请求，不传数据。 */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .build();

    public IndexBuildDispatcher(NodeRegistry nodeRegistry, RoundStore roundStore,
                                ConvergenceChecker checker) {
        this.nodeRegistry = nodeRegistry;
        this.roundStore = roundStore;
        this.checker = checker;
    }

    // ------------------------------------------------------------------ 对外

    /** 派发结果。{@code failed} 的 value 是**给人看的原因文本**，不是地址。 */
    public record DispatchResult(Status status, String indexerRole, long round, int shardCount,
                                 Map<String, String> dispatched, Map<String, String> failed,
                                 String message) {

        public enum Status {
            /** 所有分片都派出去了。 */
            DISPATCHED,
            /** 部分分片派出去了，其余见 {@code failed}。 */
            PARTIAL,
            /** 一个分片都没派出去。 */
            REJECTED,
            /** 请求体不对（索引器回的 400），{@code message} 是索引器的原话。 */
            BAD_REQUEST,
            /** 上一次派发还没结束。 */
            BUSY,
            /** **上一轮还没收敛**，现在不允许开新一轮（{@code message} 里有是哪一轮）。 */
            UNCONVERGED
        }

        static DispatchResult busy() {
            return new DispatchResult(Status.BUSY, null, 0, 0, Map.of(), Map.of(), null);
        }

        static DispatchResult rejected(String indexerRole, int shardCount, String message) {
            return new DispatchResult(Status.REJECTED, indexerRole, 0, shardCount,
                    Map.of(), Map.of(), message);
        }

        static DispatchResult badRequest(String indexerRole, long round, int shardCount, String message) {
            return new DispatchResult(Status.BAD_REQUEST, indexerRole, round, shardCount,
                    Map.of(), Map.of(), message);
        }

        static DispatchResult unconverged(String indexerRole, long round, int shardCount, String message) {
            return new DispatchResult(Status.UNCONVERGED, indexerRole, round, shardCount,
                    Map.of(), Map.of(), message);
        }
    }

    /** 最近一次派发出去的请求 —— {@link RoundTracker} 重下发时要用它。 */
    public record Dispatched(String indexerRole, long round, int shardCount, JSONObject request) {
    }

    /** 某个 role 最近一次派发的请求；从没派过（或协调器刚重启）→ {@code null}。 */
    public Dispatched lastDispatch(String indexerRole) {
        return lastDispatch.get(indexerRole);
    }

    /** 这个 role 是不是本协调器认识的一类索引器。给 controller 做参数校验用。 */
    public static boolean supports(String indexerRole) {
        return ROLE_TEXT.equals(indexerRole) || ROLE_VECTOR.equals(indexerRole);
    }

    /**
     * 派发给本 role 的每个分片。
     *
     * @param indexRequest 给索引器的请求体（**原样转发**，除了本方法会补一个 {@code round}）。
     *                     本方法不修改传入的对象。
     * @return 见 {@link DispatchResult.Status}
     */
    public DispatchResult dispatch(String indexerRole, int shardCount, JSONObject indexRequest) {
        if (!supports(indexerRole)) {
            throw new IllegalArgumentException("不认识的索引器角色：" + indexerRole);
        }
        if (!dispatchPermit.tryAcquire()) {
            return DispatchResult.busy();
        }
        try {
            // 闸门只挡**开新一轮**：上一轮没收敛就先别开下一轮（否则两轮交错，
            // 召回端的"当前版本 = 目录名最大"会让它们互相踩）。见 gateOnPreviousRound。
            DispatchResult blocked = gateOnPreviousRound(indexerRole, shardCount);
            if (blocked != null) {
                return blocked;
            }

            long round;
            try {
                round = roundStore.startNewRound(indexerRole);
            } catch (RuntimeException e) {
                // 轮次推不上去就**一个分片都不派** —— 派了的话索引器会按一个我们没记下来的轮次
                // 建索引，而 round tracker 判据看的是注册中心里那个数，两边对不上，永远收敛不了。
                log.error("{} 轮次推进失败，整轮不派发", indexerRole, e);
                return DispatchResult.rejected(indexerRole, shardCount, "轮次推进失败：" + e.getMessage());
            }

            // 记下来 —— 轮次已经推进了（= 这一轮从此刻起"欠着"），无论下面派发成不成功，
            // tracker 都得能重下发它。所以位置在**推进成功之后、派发之前**。
            lastDispatch.put(indexerRole,
                    new Dispatched(indexerRole, round, shardCount, new JSONObject(indexRequest)));
            // 分片数也落进注册中心：tracker 与闸门都要按分片判断，而且**协调器重启后也得知道**
            // （本轮用的是调用方给的分片数，不一定等于配置里那个）。见 RoundStore#writeShardCount。
            roundStore.writeShardCount(indexerRole, shardCount);

            return sendToShards(indexerRole, round, shardCount, indexRequest);
        } finally {
            dispatchPermit.release();
        }
    }

    /**
     * 重下发**已经存在的**某一轮（{@link RoundTracker} 专用）。**不推轮次、不过闸门。**
     *
     * <h2>这两点都是必须的，少一个就死锁或跑偏</h2>
     * <ul>
     *   <li><b>不推轮次</b>：tracker 的目的是让第 R 轮收敛，不是制造第 R+1 轮。
     *       推了的话索引器会按新轮次重建 —— 而"已建成的 R 就在盘上、只需重推"这个
     *       {@link IndexService} 那边的短路（§7.2c）就永远命不中。</li>
     *   <li><b>不过闸门</b>：闸门说"第 R 轮没收敛就不许开新一轮"，而重下发正是**为了**
     *       让第 R 轮收敛 —— 让它过闸门是**自相矛盾**：R 不收敛 → 拦住重下发 → R 永远不收敛。
     *       这个死锁在实机上撞到过。</li>
     * </ul>
     *
     * <p>包级可见：这是内部通路，外部入口只有 {@link #dispatch}。
     */
    DispatchResult redispatch(String indexerRole, long round, int shardCount, JSONObject indexRequest) {
        if (!supports(indexerRole)) {
            throw new IllegalArgumentException("不认识的索引器角色：" + indexerRole);
        }
        if (!dispatchPermit.tryAcquire()) {
            return DispatchResult.busy();
        }
        try {
            return sendToShards(indexerRole, round, shardCount, indexRequest);
        } finally {
            dispatchPermit.release();
        }
    }

    // ------------------------------------------------------------------ 干活

    /** 把请求发给每个分片的一个随机副本。{@code round} 只用于日志与请求体。 */
    private DispatchResult sendToShards(String indexerRole, long round, int shardCount,
                                        JSONObject indexRequest) {
        // 副本的 round 从这里开始跟着请求走：索引器写进索引目录 → 随 zip 落到召回端 →
        // 召回端的 /health 报出来，tracker 就靠它判"这一轮到底有没有人建成"。见方案 §7.3。
        JSONObject body = new JSONObject(indexRequest);   // 拷一份，别改调用方的对象
        body.put("round", round);

        String endpoint = endpointOf(indexerRole);
        Map<String, Map<String, String>> byShard = shardsOf(indexerRole);
        Map<String, String> dispatched = new LinkedHashMap<>();
        Map<String, String> failed = new LinkedHashMap<>();

        long t0 = System.currentTimeMillis();
        for (int i = 0; i < shardCount; i++) {
            String shard = "shard-" + i;
            Map<String, String> replicas = byShard.get(shard);
            if (replicas == null || replicas.isEmpty()) {
                failed.put(shard, "没有在线副本");
                continue;
            }

            // 同一分片的副本完全等价（构建结果会推给**所有**在线副本），所以随机挑一个 ——
            // 固定取字典序最小那个会让 id-0 承担全部构建，其余副本永远闲着。
            List<Map.Entry<String, String>> candidates = new ArrayList<>(replicas.entrySet());
            Collections.shuffle(candidates, ThreadLocalRandom.current());

            String reason = null;
            boolean ok = false;
            for (Map.Entry<String, String> replica : candidates) {
                String id = replica.getKey();
                PostResult result = postOne(replica.getValue(), endpoint, body);
                switch (result.kind()) {
                    case ACCEPTED -> {
                        dispatched.put(shard, replica.getValue());
                        log.info("{} {} 派发给 {}（{}）", indexerRole, shard, id, replica.getValue());
                        ok = true;
                    }
                    case BAD_REQUEST -> {
                        // 请求体的问题 —— 换副本也一样错。立刻中止整轮，把索引器的原话透出去。
                        log.error("{} {} 返回 400，中止整轮不派发：{}", indexerRole, shard, result.detail());
                        return DispatchResult.badRequest(indexerRole, round, shardCount, result.detail());
                    }
                    // 409 是软失败（节点忙），连不上/超时也可能只是这个副本的问题 —— 都换下一个副本试
                    case BUSY, UNREACHABLE -> reason = id + " " + result.detail();
                }
                if (ok) {
                    break;
                }
            }
            if (!ok) {
                failed.put(shard, candidates.size() + " 个副本都不可用：" + reason);
                log.warn("{} {} 派发不出去（{} 个副本都试过）：{}", indexerRole, shard, candidates.size(), reason);
            }
        }

        DispatchResult.Status status = dispatched.isEmpty() ? DispatchResult.Status.REJECTED
                : (failed.isEmpty() ? DispatchResult.Status.DISPATCHED : DispatchResult.Status.PARTIAL);
        log.info("{} 第 {} 轮派发完成：{}（成功 {} 片，失败 {} 片，用时 {}ms）",
                indexerRole, round, status, dispatched.size(), failed.size(),
                System.currentTimeMillis() - t0);
        return new DispatchResult(status, indexerRole, round, shardCount, dispatched, failed, null);
    }

    /**
     * 闸门：**上一轮还没收敛就不许开新一轮**。
     *
     * <h2>为什么要有它</h2>
     * 两个轮次同时在建会互相踩：后派发的那个 stamp 更晚，召回端的"当前版本"取目录名最大的那份，
     * 于是**先派发的那一轮哪怕后跑完，也会被当成旧版本**；反过来，如果它跑得更晚，
     * 就会把已经被新一轮更新的索引**推回去**。单轮执行把这一整类交错消掉。
     *
     * <h2>逃逸口是必须的，不是可选的</h2>
     * 「没收敛就不许开下一轮」碰上**永久坏**的分片（数据源里一条都不属于它 / 磁盘满 / 配置错）
     * 就是死锁：这一轮永远不收敛 → 这个 role **再也建不了索引**。
     * 所以 {@link RoundTracker} 催满上限时会写一个「放弃标记」，这里看到标记就放行 ——
     * 那不是"这一轮成了"，是"这一轮不再追究了"。
     *
     * <h2>代价</h2>
     * 每次派发前要查一轮召回副本的 {@code /health}（N 个分片 × 副本数）。这是个运维动作，
     * 秒级延迟可以接受；比"悄悄建了两份、还把旧的那份推到线上"划算得多。
     *
     * @return 要拦下就返回一个 {@link DispatchResult.Status#UNCONVERGED}；放行返回 {@code null}
     */
    private DispatchResult gateOnPreviousRound(String indexerRole, int shardCount) {
        long current;
        try {
            current = roundStore.read(indexerRole);
        } catch (RuntimeException e) {
            log.error("{} 读不到当前轮次，按「上一轮未收敛」拦下", indexerRole, e);
            return DispatchResult.unconverged(indexerRole, 0, shardCount,
                    "读不到当前轮次（注册中心有问题）：" + e.getMessage());
        }
        if (current <= 0) {
            return null;                        // 还从没建过，没什么可拦的
        }
        if (roundStore.abandonedRound(indexerRole) == current) {
            log.warn("{} 第 {} 轮已被 tracker 放弃，放行新一轮", indexerRole, current);
            return null;
        }

        // 用**那一轮**的 shardCount 去判，不是这次请求的 —— 判的是"上一轮收没收敛"，
        // 而上一轮是按它自己的分片数派发的。两者不同的话（调用方改了 shardCount），
        // 拿新分片数去查旧轮次会查到一堆"没有在线副本"，误判成未收敛。
        int shardCountOfCurrentRound = roundStore.readShardCount(indexerRole);
        if (shardCountOfCurrentRound <= 0) {
            // 注册中心里没有记录（老数据 / 读失败）。退到内存里那次派发的分片数，
            // 再退到配置 —— 只是兜底，正常路径走不到这里。
            Dispatched last = lastDispatch.get(indexerRole);
            shardCountOfCurrentRound =
                    (last != null && last.round() == current) ? last.shardCount() : shardCount;
        }

        if (checker.isConverged(indexerRole, shardCountOfCurrentRound, current)) {
            return null;
        }
        log.warn("{} 第 {} 轮还没收敛，拦下新一轮（shardCount={}）",
                indexerRole, current, shardCountOfCurrentRound);
        return DispatchResult.unconverged(indexerRole, current, shardCount,
                "第 " + current + " 轮还没收敛；等它收敛，或者等 tracker 催满 "
                        + "round-max-attempts 次放弃后重试");
    }

    /** 打一个索引器。**不抛异常** —— 所有失败都变成 {@link Kind}，让调用方用同一条路径处理。 */
    private PostResult postOne(String address, String endpoint, JSONObject body) {
        String url = "http://" + address + endpoint;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(requestTimeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return switch (response.statusCode()) {
                case 202 -> new PostResult(Kind.ACCEPTED, null);
                // 索引器的原话比什么都强（它会说"vectorFields[0]「tittle」不在 fieldList 里"）
                case 400 -> new PostResult(Kind.BAD_REQUEST, response.body());
                case 409 -> new PostResult(Kind.BUSY, "正忙（409）");
                default -> {
                    log.warn("派发 {} 返回 {}：{}", address, response.statusCode(), response.body());
                    yield new PostResult(Kind.UNREACHABLE, "返回 " + response.statusCode());
                }
            };
        } catch (Exception e) {
            log.warn("派发 {} 失败：{}", address, e.toString());
            return new PostResult(Kind.UNREACHABLE, e.getClass().getSimpleName());
        }
    }

    private enum Kind { ACCEPTED, BUSY, UNREACHABLE, BAD_REQUEST }

    private record PostResult(Kind kind, String detail) {
    }

    /** role → 索引器端点。显式映射，别用字符串拼（同 {@code ConvergenceChecker#recallRoleOf} 的理由）。 */
    private static String endpointOf(String indexerRole) {
        return switch (indexerRole) {
            case ROLE_TEXT -> "/textIndex";
            case ROLE_VECTOR -> "/vectorIndex";
            default -> throw new IllegalArgumentException("不认识的索引器角色：" + indexerRole);
        };
    }

    /** role → 本分片的副本表。取的是 {@link NodeRegistry} 的**上一轮快照**（最多 60 秒前）。 */
    private Map<String, Map<String, String>> shardsOf(String indexerRole) {
        return switch (indexerRole) {
            case ROLE_TEXT -> nodeRegistry.textIndexers();
            case ROLE_VECTOR -> nodeRegistry.vectorIndexers();
            default -> throw new IllegalArgumentException("不认识的索引器角色：" + indexerRole);
        };
    }
}
