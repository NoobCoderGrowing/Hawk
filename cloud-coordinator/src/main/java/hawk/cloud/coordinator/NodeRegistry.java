package hawk.cloud.coordinator;

import io.github.noobcodergrowing.jregistryclient.JRegistryClient;
import io.github.noobcodergrowing.jregistrycore.KV;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 JRegistry 拉取四类节点，每类各维护**一个独立的字典**，按固定间隔轮询跟踪变化。
 *
 * <h2>四种 role 与四个字典</h2>
 * 见仓库根目录的 {@code roleType.md}，四类节点都在 {@code cloud} 根路径下各占一棵子树：
 * <pre>
 *   cloud/text-indexer     倒排索引器       → {@link #textIndexers()}
 *   cloud/vector-indexer   向量索引器       → {@link #vectorIndexers()}
 *   cloud/text-recall      倒排搜索分片     → {@link #textRecalls()}
 *   cloud/vector-recall    向量索引分片     → {@link #vectorRecalls()}
 * </pre>
 *
 * <h2>每个字典内部按 shard-num 分类</h2>
 * 注册中心里的 key 是 {@code /cloud/{role}/{shard-num}/{shard-id}}，值是该副本的 {@code ip:port}。
 * 所以每个字典是**两层**：外层按「第几个分片」分，内层按「该分片的第几个副本」分。
 * <pre>
 *   textIndexers() = {
 *       "shard-1" -> { "id-0" -> "127.0.0.1:8091", "id-1" -> "127.0.0.1:8093" },
 *       "shard-2" -> { "id-0" -> "127.0.0.1:8095" }
 *   }
 * </pre>
 * 注册时写的是带前导斜杠的 {@code /cloud/text-indexer/...}，但注册中心内部按键路径分段存储，
 * 列出来时不带前导斜杠 —— 两种写法在 {@code get}/{@code ls} 里等价（{@code KeyPath.split} 会剥掉首尾斜杠）。
 *
 * <h2>怎么拿到「所有」节点</h2>
 * 用 {@code JRegistryClient.listAll(path)}（1.0.4 新增，走 {@code type="ls"}）列出某个路径
 * 下的所有键值对。它返回整棵子树里的**叶子**，也就是每个已登记节点的 {@code ip:port}。
 *
 * <h2>变化的判定</h2>
 * 每轮对每个 role 拉全量，展平成 {@code shard-num/shard-id -> ip:port} 后与上一轮逐个比对，
 * 把**上线 / 下线 / 地址变更**记进日志；字典整体替换。拉取失败时**保留上一轮快照**，不清空。
 */
@Component
public class NodeRegistry implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(NodeRegistry.class);

    /** 节点在注册中心里的根路径（不要带前导斜杠，listAll 返回的 key 就是这个形式）。 */
    private static final String ROOT = "cloud";

    /** 四种节点 role。见 roleType.md —— 这是一组封闭取值，所以写死在代码里而非配置。 */
    private static final String ROLE_TEXT_INDEXER = "text-indexer";
    private static final String ROLE_VECTOR_INDEXER = "vector-indexer";
    private static final String ROLE_TEXT_RECALL = "text-recall";
    private static final String ROLE_VECTOR_RECALL = "vector-recall";

    /** 单次 RPC 等待上限。 */
    private static final long TASK_TIMEOUT_MS = 3_000;

    /** TCP 建连超时。节点表里每个节点各算一次，所以取值别太大。 */
    private static final int CONNECT_TIMEOUT_MS = 3_000;

    @Value("${hawk.cloud.coordinator.registry-address:}")
    private String registryAddress;

    /** 长连接，跨轮询复用；掉线由 ensureConnected() 重连。 */
    private JRegistryClient client;

    /** 未配置 registry-address 时只警告一次，不要每分钟刷一行。 */
    private boolean warnedUnconfigured;

    /**
     * 改字典的互斥锁。两条路径会改字典：{@link #poll()} 整体替换、{@link #condemn} 摘掉一个节点。
     * 不加锁的话两者会互相覆盖 —— 健康检查刚判死的节点会被紧接着的轮询按注册中心旧内容写回来。
     */
    private final Object dictLock = new Object();

    // ---------------------------------------------------------------- 四个字典
    // 每个都是 shard-num -> (shard-id -> ip:port) 两层。整体替换（volatile 换引用），读侧不加锁。

    /** 倒排索引器：shard-num -> (shard-id -> ip:port)。 */
    private volatile Map<String, Map<String, String>> textIndexers = Map.of();

    /** 向量索引器：shard-num -> (shard-id -> ip:port)。 */
    private volatile Map<String, Map<String, String>> vectorIndexers = Map.of();

    /** 倒排搜索分片：shard-num -> (shard-id -> ip:port)。 */
    private volatile Map<String, Map<String, String>> textRecalls = Map.of();

    /** 向量索引分片：shard-num -> (shard-id -> ip:port)。 */
    private volatile Map<String, Map<String, String>> vectorRecalls = Map.of();

    /**
     * 固定间隔轮询。用 fixedDelay 而非 fixedRate：一轮慢一点（注册中心卡顿）不该导致下一轮
     * 立刻补跑堆叠。
     */
    @Scheduled(fixedDelayString = "${hawk.cloud.coordinator.poll-interval-ms:60000}")
    public void poll() {
        if (registryAddress == null || registryAddress.isBlank()) {
            if (!warnedUnconfigured) {
                log.warn("hawk.cloud.coordinator.registry-address 未配置，跳过节点轮询");
                warnedUnconfigured = true;
            }
            return;
        }

        if (!ensureConnected()) {
            log.warn("连不上注册中心 {}，本轮跳过（保留上一轮快照：{}）",
                    registryAddress, summarize(textIndexers, vectorIndexers, textRecalls, vectorRecalls));
            return;
        }

        // 拉取与替换都放在锁里：fetch 只有几十毫秒，但期间若被 condemn 插进来摘节点，
        // 随后这里的整体替换会把它又写回去。
        synchronized (dictLock) {
            Map<String, Map<String, String>> newTextIndexers = fetchNodes(ROLE_TEXT_INDEXER);
            Map<String, Map<String, String>> newVectorIndexers = fetchNodes(ROLE_VECTOR_INDEXER);
            Map<String, Map<String, String>> newTextRecalls = fetchNodes(ROLE_TEXT_RECALL);
            Map<String, Map<String, String>> newVectorRecalls = fetchNodes(ROLE_VECTOR_RECALL);

            logChanges(ROLE_TEXT_INDEXER, textIndexers, newTextIndexers);
            logChanges(ROLE_VECTOR_INDEXER, vectorIndexers, newVectorIndexers);
            logChanges(ROLE_TEXT_RECALL, textRecalls, newTextRecalls);
            logChanges(ROLE_VECTOR_RECALL, vectorRecalls, newVectorRecalls);

            this.textIndexers = newTextIndexers;
            this.vectorIndexers = newVectorIndexers;
            this.textRecalls = newTextRecalls;
            this.vectorRecalls = newVectorRecalls;

            log.debug("轮询完成：{}",
                    summarize(newTextIndexers, newVectorIndexers, newTextRecalls, newVectorRecalls));
        }
    }

    /**
     * 判死一个节点：先让注册中心删掉对应的 kv，再从本地字典摘掉它。
     *
     * <p>删除走的是 {@code JRegistryClient.delete(key)}，key 用带前导斜杠的
     * {@code /cloud/{role}/{shard-num}/{shard-id}} —— 与登记时写入的形式一致。
     * （{@code KeyPath.split} 会剥掉首尾斜杠，所以和 {@code ls} 返回的不带斜杠形式等价。）
     *
     * <p>注意 {@code delete} 是单向发送、不等回执：调用返回不代表注册中心已落库。若删除没生效，
     * 下一轮 {@link #poll()} 会把这个节点又拉回来，再被判死一次 —— 会形成慢速自愈循环，
     * 但不会误删活节点。
     */
    public void condemn(String role, String shardNum, String shardId) {
        String absoluteKey = "/" + ROOT + "/" + role + "/" + shardNum + "/" + shardId;
        if (client != null && client.isConnected()) {
            client.delete(absoluteKey);
        } else {
            log.warn("注册中心未连接，只从本地字典移除，未通知注册中心：{}", absoluteKey);
        }

        synchronized (dictLock) {
            switch (role) {
                case ROLE_TEXT_INDEXER -> textIndexers = without(textIndexers, shardNum, shardId);
                case ROLE_VECTOR_INDEXER -> vectorIndexers = without(vectorIndexers, shardNum, shardId);
                case ROLE_TEXT_RECALL -> textRecalls = without(textRecalls, shardNum, shardId);
                case ROLE_VECTOR_RECALL -> vectorRecalls = without(vectorRecalls, shardNum, shardId);
                default -> log.warn("未知 role，未从字典移除：{}", absoluteKey);
            }
        }
    }

    /** 复制一份字典并摘掉 {@code shardNum/shardId}；该分片空了就一并去掉分片。 */
    private static Map<String, Map<String, String>> without(Map<String, Map<String, String>> byShard,
                                                            String shardNum, String shardId) {
        Map<String, String> replicas = byShard.get(shardNum);
        if (replicas == null || !replicas.containsKey(shardId)) {
            return byShard;   // 没有可摘的，原样返回
        }
        Map<String, Map<String, String>> copy = new LinkedHashMap<>(byShard);
        Map<String, String> remaining = new LinkedHashMap<>(replicas);
        remaining.remove(shardId);
        if (remaining.isEmpty()) {
            copy.remove(shardNum);
        } else {
            copy.put(shardNum, remaining);
        }
        return copy;
    }

    /** 列出某个 role 子树下的所有叶子，按 shard-num / shard-id 两层归类。 */
    private Map<String, Map<String, String>> fetchNodes(String role) {
        String path = ROOT + "/" + role;
        List<KV> kvs = client.listAll(path);
        if (kvs == null) {
            // listAll 把「路径不存在」和「请求失败」都表示成 null。走到这里说明连接是好的
            // （ensureConnected 已确认），所以按「该 role 当前一个节点都没有」处理。
            return Map.of();
        }
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (KV kv : kvs) {
            String absolute = absoluteKey(path, kv.getKey());
            String[] shardAndId = splitShardAndId(role, absolute);
            if (shardAndId == null) {
                log.warn("[{}] 这个键不是 {}/{}/{shard-num}/{shard-id} 的形状，已跳过：{}",
                        role, ROOT, role, absolute);
                continue;
            }
            out.computeIfAbsent(shardAndId[0], k -> new LinkedHashMap<>())
                    .put(shardAndId[1], kv.getData() == null ? "" : new String(kv.getData(), StandardCharsets.UTF_8));
        }
        return out;
    }

    /**
     * 从绝对 key（{@code cloud/{role}/{shard-num}/{shard-id}}）里取出后两段。
     * 层级对不上时返回 {@code null}。静态以便单测。
     *
     * @return {@code [shard-num, shard-id]}
     */
    static String[] splitShardAndId(String role, String absoluteKey) {
        String[] parts = absoluteKey.split("/");
        if (parts.length != 4 || !ROOT.equals(parts[0]) || !role.equals(parts[1])) {
            return null;
        }
        return new String[]{parts[2], parts[3]};
    }

    /**
     * 把 listAll 返回的 key 还原成绝对路径。
     *
     * <p>服务端 {@code StateMachine.ls} 的注释说返回的是绝对完整路径，但实测**查询深度 ≥ 2 时
     * 最外层那段会丢**：{@code ls("cloud/text-indexer")} 返回 {@code text-indexer/shard-1/id-0}，
     * 而 {@code ls("cloud")} 返回 {@code cloud/text-indexer/shard-1/id-0}。原因是 collectKVs 的
     * prefix 从空串起步，而不是从「被查节点的父路径」起。
     *
     * <p>这里两种都兼容：已经是绝对路径就原样用，否则补上查询路径的父段。服务端哪天修了，
     * 这段照样成立。
     */
    private static String absoluteKey(String queryPath, String returned) {
        if (returned.equals(queryPath) || returned.startsWith(queryPath + "/")) {
            return returned;
        }
        int slash = queryPath.lastIndexOf('/');
        return slash < 0 ? returned : queryPath.substring(0, slash + 1) + returned;
    }

    /** 与上一轮快照比对，把上线 / 下线 / 地址变更记进日志。 */
    private static void logChanges(String role,
                                   Map<String, Map<String, String>> before,
                                   Map<String, Map<String, String>> after) {
        Map<String, String> flatBefore = flatten(role, before);
        Map<String, String> flatAfter = flatten(role, after);

        for (Map.Entry<String, String> entry : flatAfter.entrySet()) {
            String old = flatBefore.get(entry.getKey());
            if (old == null) {
                log.info("[{}] 上线 {} -> {}", role, entry.getKey(), entry.getValue());
            } else if (!old.equals(entry.getValue())) {
                log.info("[{}] 地址变更 {} : {} -> {}", role, entry.getKey(), old, entry.getValue());
            }
        }
        for (String key : flatBefore.keySet()) {
            if (!flatAfter.containsKey(key)) {
                log.info("[{}] 下线 {}", role, key);
            }
        }
    }

    /** 展平成「绝对 key -> ip:port」，只为比对和打日志。 */
    private static Map<String, String> flatten(String role, Map<String, Map<String, String>> byShard) {
        Map<String, String> flat = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> shard : byShard.entrySet()) {
            for (Map.Entry<String, String> replica : shard.getValue().entrySet()) {
                flat.put(ROOT + "/" + role + "/" + shard.getKey() + "/" + replica.getKey(),
                        replica.getValue());
            }
        }
        return flat;
    }

    /** 每个字典几个分片、几个节点，用于日志。 */
    private static String summarize(Map<String, Map<String, String>> textIndexers,
                                    Map<String, Map<String, String>> vectorIndexers,
                                    Map<String, Map<String, String>> textRecalls,
                                    Map<String, Map<String, String>> vectorRecalls) {
        return describe(ROLE_TEXT_INDEXER, textIndexers)
                + " / " + describe(ROLE_VECTOR_INDEXER, vectorIndexers)
                + " / " + describe(ROLE_TEXT_RECALL, textRecalls)
                + " / " + describe(ROLE_VECTOR_RECALL, vectorRecalls);
    }

    private static String describe(String role, Map<String, Map<String, String>> byShard) {
        int nodes = 0;
        for (Map<String, String> replicas : byShard.values()) {
            nodes += replicas.size();
        }
        return role + " " + byShard.size() + "片/" + nodes + "节点";
    }

    /** 连上就复用，断开就按候选列表逐个重连。 */
    private boolean ensureConnected() {
        if (client != null && client.isConnected()) {
            return true;
        }
        if (client != null) {
            // 旧连接已死：先关掉，免得 NettyClient 自己的重连线程留着不放。
            client.shutdown();
            client = null;
        }

        List<InetSocketAddress> candidates = RegistryAddresses.parse(registryAddress);
        if (candidates.isEmpty()) {
            log.error("registry-address 解析不出任何 host:port，实际为“{}”", registryAddress);
            return false;
        }
        for (InetSocketAddress candidate : candidates) {
            JRegistryClient c = new JRegistryClient(
                    candidate.getHostString(), candidate.getPort(), TASK_TIMEOUT_MS, CONNECT_TIMEOUT_MS);
            if (c.connect()) {
                client = c;
                log.info("已连上注册中心 {}", format(candidate));
                return true;
            }
            c.shutdown();
        }
        return false;
    }

    private static String format(SocketAddress address) {
        return address.toString().replaceFirst("^/", "");
    }

    // ---------------------------------------------------------------- 对外只读访问

    /** 倒排索引器：shard-num -> (shard-id -> ip:port)。 */
    public Map<String, Map<String, String>> textIndexers() {
        return textIndexers;
    }

    /** 向量索引器：shard-num -> (shard-id -> ip:port)。 */
    public Map<String, Map<String, String>> vectorIndexers() {
        return vectorIndexers;
    }

    /** 倒排搜索分片：shard-num -> (shard-id -> ip:port)。 */
    public Map<String, Map<String, String>> textRecalls() {
        return textRecalls;
    }

    /** 向量索引分片：shard-num -> (shard-id -> ip:port)。 */
    public Map<String, Map<String, String>> vectorRecalls() {
        return vectorRecalls;
    }

    /** 一个节点的引用，供健康检查遍历四个字典用。 */
    public record NodeRef(String role, String shardNum, String shardId, String address) {
    }

    /** 四个字典里的全部节点。 */
    public List<NodeRef> allNodes() {
        List<NodeRef> out = new ArrayList<>();
        collect(out, ROLE_TEXT_INDEXER, textIndexers);
        collect(out, ROLE_VECTOR_INDEXER, vectorIndexers);
        collect(out, ROLE_TEXT_RECALL, textRecalls);
        collect(out, ROLE_VECTOR_RECALL, vectorRecalls);
        return out;
    }

    private static void collect(List<NodeRef> out, String role, Map<String, Map<String, String>> byShard) {
        for (Map.Entry<String, Map<String, String>> shard : byShard.entrySet()) {
            for (Map.Entry<String, String> replica : shard.getValue().entrySet()) {
                out.add(new NodeRef(role, shard.getKey(), replica.getKey(), replica.getValue()));
            }
        }
    }

    @Override
    public void destroy() {
        if (client != null) {
            client.shutdown();
        }
    }
}
