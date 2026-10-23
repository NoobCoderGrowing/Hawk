package hawk.cloud.recall;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import io.github.noobcodergrowing.jregistrycore.KV;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 定时扫注册中心，发现哪个索引器有比自己更新的索引就拉下来。
 * 方案见 {@code DESIGN.md} §4.3。
 *
 * <pre>
 * 每 pull-interval-ms：
 *     我现在的 round = IndexStore.newestRound()（没有当 0）
 *     扫 cloud/index-ready/{indexerRole}/{本分片}/*  → shardId -> {round, version}
 *     从 cloud/{indexerRole}/{本分片}/* 拿 shardId -> ip:port（跳过离线的）
 *     取 round 最大的那个；不比我的高 → 收工
 *     拉下来 → 校验 → 原子安装
 *     任何一步失败 → 记一条日志，收工，等下一次
 * </pre>
 *
 * <h2>为什么要"拉"而不是"推"</h2>
 * 拉取把"谁该有什么"交回给最清楚自己状态的那一方。索引器**不再需要知道有哪些召回节点**；
 * 加一个召回副本对索引器零影响，而且新副本**下一个周期就能自己把索引拉回来** ——
 * 这是"推"做不到的（推的模型里新副本要等下一次重建）。
 *
 * <h2>失败只是一条日志，没有重试上限</h2>
 * 定时扫描本身就是个天然的、无限的、幂等的重试循环。**这里不该有次数上限** ——
 * 和协调器那边"重下发要设上限"是相反的，因为代价完全不同：这边重试的是一次**传输**（廉价、幂等），
 * 那边重试的是**重算一遍索引**（向量 10 万篇 16 分钟）。
 *
 * <h2>⚠️ 一个已知的、刻意的取舍</h2>
 * <b>拉不动的副本会一直服务陈旧索引，而且没有任何地方会报错。</b>
 * 兜底是外部的：本节点的 {@code /health} 一直在报 {@code round}，监控拿它和注册中心里的当前轮次一比
 * 就知道谁落后了。**不要在协调器里再加一遍这个检查** —— 那等于把删掉的复杂度请回来。
 */
@Component
public class IndexPullJob {

    private static final Logger log = LoggerFactory.getLogger(IndexPullJob.class);

    /** 索引器"我这儿有第 R 轮"的登记路径根。**必须与 indexer 侧 IndexReadyRegistry 一致。** */
    private static final String READY_ROOT = "cloud/index-ready";

    /**
     * 节点登记的根路径。
     *
     * <p>⚠️ <b>不带这个前缀注册中心会静默返回空</b>（`ls` 回包里 `success:false`，客户端把它变成
     * 一个空列表）—— 表现是"拉取任务明明在跑、却什么都不做、也不报错"。实机上撞到过。
     */
    private static final String NODE_ROOT = "cloud";

    /** 磁盘可用空间的**地板**：低于它就干脆不开始拉。 */
    private static final long MIN_FREE_BYTES_FLOOR = 512L * 1024 * 1024;

    /** 超过这个年龄的 {@code .staging-*} 当残骸清掉（拉取中断留下的）。 */
    private static final long STALE_STAGING_MS = 60 * 60 * 1000L;

    private final LocalIndexStore local;
    private final RegistryRegistrar registrar;
    private final IndexFetchClient fetchClient;

    /**
     * 两个检索器都要注入 —— **本节点按 role 只会用到其中一个**。
     *
     * <p>（为什么两个都注入而不是"按 role 条件注入"：它们都是 {@code @Component}，
     * 而"本节点是哪个 role"是配置，不是 bean 的存在性。让 Spring 做条件化会把配置
     * 泄漏到装配层，不如在这里按 role 选。）
     */
    private final LocalSearcher textSearcher;
    private final LocalVectorSearcher vectorSearcher;

    /** 检查跑在这里，**不占 {@code scheduling-1}** —— 那个单线程池还要跑节点心跳重登记。 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "index-pull");
        t.setDaemon(true);
        return t;
    });

    public IndexPullJob(LocalIndexStore local, RegistryRegistrar registrar,
                        IndexFetchClient fetchClient,
                        LocalSearcher textSearcher, LocalVectorSearcher vectorSearcher) {
        this.local = local;
        this.registrar = registrar;
        this.fetchClient = fetchClient;
        this.textSearcher = textSearcher;
        this.vectorSearcher = vectorSearcher;
    }

    /**
     * 启动时就预热一次。
     *
     * <h2>为什么"装好之后预热"还不够</h2>
     * 那是**换版**的场景。还有**重启**：节点重启后索引还在盘上（不需要重新拉），
     * 但进程里的检索器是空的 —— 第一个查询照样要付那一秒多的冷启代价。
     * 部署、崩溃重启都会走这条，而且是**每一次**都走。
     *
     * <p>提交到线程池而不是同步做：启动流程不该为了省第一个查询而被拖慢 1.5 秒。
     * 真没有索引时它会立刻抛（那条 catch 会记一条 warn）—— 无害。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpOnStartup() {
        executor.submit(this::warmUpSearcher);
    }

    /** {@code @Scheduled} 只负责提交，活在线程池里干。 */
    @Scheduled(fixedDelayString = "${hawk.cloud.recall.pull-interval-ms:60000}",
               initialDelayString = "${hawk.cloud.recall.pull-interval-ms:60000}")
    public void schedulePull() {
        executor.submit(() -> {
            try {
                pullOnce();
            } catch (Throwable t) {
                // 定时任务里抛出去就再也不会被调度了 —— 必须在这里兜住
                log.error("拉取索引失败", t);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    /** 单跑一次（测试/运维用）。 */
    void pullOnce() {
        String indexerRole = indexerRoleOf(local.role());
        if (indexerRole == null) {
            log.warn("本节点 role={} 不是召回角色，不拉索引", local.role());
            return;
        }

        Path shardDir = local.store().shardDir();
        local.store().cleanStaleStaging(STALE_STAGING_MS);   // 先清上一次留下的残骸

        Long mineRound = local.store().newestRound();
        long mine = mineRound == null ? 0 : mineRound;

        Map<String, Advertised> advertised = readAdvertised(indexerRole);
        if (advertised.isEmpty()) {
            return;                                          // 没有索引器登记，收工
        }
        Map<String, String> addresses = readAddresses(indexerRole);

        // 取 round 最大的那个候选。同分片的索引器副本内容**等价**（谁建的都一样），
        // 所以这里只挑最新的，不挑特定副本。
        String bestId = null;
        Advertised best = null;
        for (Map.Entry<String, Advertised> e : advertised.entrySet()) {
            String address = addresses.get(e.getKey());
            if (address == null) {
                continue;                                    // 节点登记里没有它（或已离线）→ 拉不到
            }
            if (best == null || e.getValue().round() > best.round()) {
                bestId = e.getKey();
                best = e.getValue();
            }
        }
        if (best == null) {
            // 有人登记了索引，却找不到它的地址 —— 注册中心里两条键对不上。**用 warn**：
            // 正常情况下不该发生，而且"拉取任务在跑却什么都不做"如果只打 debug 会非常难查
            // （实机上就这么栽过一次）。
            log.warn("{} 下有 {} 条就绪登记，但没有一条能对上索引器地址，跳过：{}",
                    indexerRole, advertised.size(), advertised.keySet());
            return;
        }
        if (best.round() <= mine) {
            log.debug("最新登记是第 {} 轮，本节点已是 {}，不需要拉", best.round(), mine);
            return;                                          // ★ 不降级：只拉比自己高的
        }

        if (!hasRoomOnDisk(shardDir)) {
            log.warn("本地磁盘可用空间低于地板，本轮不拉第 {} 轮", best.round());
            return;
        }

        log.info("发现第 {} 轮（本节点当前 {}），从 {}（{}）拉取", best.round(), mine, bestId,
                addresses.get(bestId));
        IndexFetchClient.Fetched fetched = fetchClient.fetch(
                addresses.get(bestId), local.shardNum(), best.round(), best.version(), shardDir);
        if (fetched == null) {
            return;                                          // 失败就放弃，等下一次
        }
        try {
            local.store().install(fetched.version(), fetched.zipFile());
            log.info("第 {} 轮已装好，当前版本 = {}", best.round(), local.store().newest());
            warmUpSearcher();
        } catch (Exception e) {
            // 解压 / 校验失败：install 已经把 staging 删掉了，这里只记日志
            log.error("装第 {} 轮失败（包已丢弃，不会留下半成品）", best.round(), e);
        } finally {
            deleteQuietly(fetched.zipFile());
        }
    }

    /**
     * **装好之后立刻在后台把检索器打开** —— 把"打开"的代价从第一个用户查询挪到这里。
     *
     * <p>不预热会怎样（实测）：换版后的第一个查询要付 mmap + 图加载 + ONNX 会话（约 1.5 秒），
     * 足以撞上协调器的分片超时（默认 3 秒），于是**那一片在这次查询里整片算 0 条** ——
     * 换一次版就漏一次。虽然协调器会在 {@code failed} 里如实报出来（不是静默），
     * 但那是可避免的损失。
     *
     * <p><b>跑在本任务自己的线程池上</b>（{@code index-pull}），不是 {@code scheduling-1} ——
     * 这里会阻塞 1 秒多，绝不能占那个单线程池。
     *
     * <p><b>失败不影响安装成功</b>：索引已经装好了，预热只是省第一个查询的时间。
     * 真失败（比如模型对不上）会在第一个查询那里以 503 的形式暴露出来，那条路本来就在。
     */
    private void warmUpSearcher() {
        try {
            if ("vector-recall".equals(local.role())) {
                vectorSearcher.warmUp();
            } else {
                textSearcher.warmUp();
            }
            log.info("检索器已预热（{}）", local.role());
        } catch (Exception e) {
            log.warn("预热检索器失败 —— 索引已装好，但第一个查询会慢，甚至撞上协调器的分片超时：{}",
                    e.getMessage());
        }
    }

    // ------------------------------------------------------------------

    /** 一个索引器副本的登记内容。{@code version} 是**版本目录名**，落地时要先校验（见下）。 */
    private record Advertised(long round, String version) {
    }

    /** 扫 {@code cloud/index-ready/{indexerRole}/{本分片}}，返回 shardId -> 登记内容。 */
    private Map<String, Advertised> readAdvertised(String indexerRole) {
        List<KV> kvs = registrar.listNodes(
                READY_ROOT + "/" + indexerRole + "/" + local.shardNum());
        if (kvs == null || kvs.isEmpty()) {
            return Map.of();
        }
        Map<String, Advertised> out = new HashMap<>();
        for (KV kv : kvs) {
            if (kv.isOffline()) {
                continue;                                    // 登记的客户端都离线了，拉不到
            }
            String id = lastSegment(kv.getKey());
            Advertised a = parse(kv.getData(), kv.getKey());
            if (id != null && a != null) {
                out.put(id, a);
            }
        }
        return out;
    }

    /** 扫节点登记 {@code cloud/{indexerRole}/{本分片}}，返回 shardId -> ip:port（跳过离线的）。 */
    private Map<String, String> readAddresses(String indexerRole) {
        List<KV> kvs = registrar.listNodes(NODE_ROOT + "/" + indexerRole + "/" + local.shardNum());
        if (kvs == null || kvs.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        for (KV kv : kvs) {
            if (kv.isOffline()) {
                continue;
            }
            String id = lastSegment(kv.getKey());
            if (id != null && kv.getData() != null) {
                out.put(id, new String(kv.getData(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static Advertised parse(byte[] data, String key) {
        if (data == null) {
            return null;
        }
        try {
            JSONObject json = JSON.parseObject(new String(data, StandardCharsets.UTF_8));
            Long round = json == null ? null : json.getLong("round");
            String version = json == null ? null : json.getString("version");
            if (round == null || version == null) {
                log.warn("{} 的登记缺 round 或 version，跳过：{}", key, json);
                return null;
            }
            // ★ 安全边界，不是格式偏好：version 会被当成**目录名**拼进路径
            //   （IndexStore.install → shardDir.resolve(version)）。它来自**注册中心**，
            //   往那儿塞一个 "../../../tmp/evil" 就能让本节点把索引装到分片目录之外。
            //   推送模型里这道路径校验在接收端（校验请求里的 stamp）；改成拉取之后
            //   名字改从注册中心来了，校验必须跟着搬过来 —— 否则就是静默地把墙拆了。
            if (!IndexStamp.isValidVersion(version)) {
                log.warn("{} 登记的版本目录名不合法，拒绝：{}", key, version);
                return null;
            }
            return new Advertised(round, version);
        } catch (RuntimeException e) {
            log.warn("{} 的登记解析失败，跳过：{}", key, e.toString());
            return null;
        }
    }

    private static String lastSegment(String key) {
        int i = key.lastIndexOf('/');
        return i < 0 || i == key.length() - 1 ? null : key.substring(i + 1);
    }

    /** 召回角色 → 索引器角色。显式映射，别用字符串替换。 */
    private static String indexerRoleOf(String recallRole) {
        return switch (recallRole) {
            case "text-recall" -> "text-indexer";
            case "vector-recall" -> "vector-indexer";
            default -> null;
        };
    }

    /**
     * 本地磁盘够不够。
     *
     * <p><b>这是个"地板"，不是"够不够装下这一包"</b> —— 拉之前并不知道包多大（要额外一次
     * HEAD 才拿得到 {@code Content-Length}）。它的作用是"磁盘快满了就别再往上堆"，
     * 而不是精确的容量预检。真装不下时写入会失败，那条路也一样是"记日志、等下一轮"。
     */
    private static boolean hasRoomOnDisk(Path dir) {
        try {
            Path p = dir.toAbsolutePath();
            while (p != null && !Files.exists(p)) {
                p = p.getParent();
            }
            return p == null || Files.getFileStore(p).getUsableSpace() >= MIN_FREE_BYTES_FLOOR;
        } catch (Exception e) {
            return true;                                     // 查不到就照拉（与推模型里 freeBytes 的 fail-open 同理）
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignored) {
            // 删不掉就留给 cleanStaleStaging
        }
    }
}
