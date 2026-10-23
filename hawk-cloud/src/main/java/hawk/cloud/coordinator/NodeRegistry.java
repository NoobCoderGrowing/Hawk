package hawk.cloud.coordinator;

import hawk.cloud.RegistryAddresses;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 JRegistry 拉取四类节点，每类各维护**一个独立的字典**，按固定间隔轮询跟踪变化。
 *
 * <h2>四种 role 与四个字典</h2>
 * 见仓库根目录的 {@code DESIGN.md §1.1}，四类节点都在 {@code cloud} 根路径下各占一棵子树：
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
 * <h2>谁在线由注册中心说了算</h2>
 * <b>协调器不再自己探活。</b>注册中心（服务端 1.0.5 起）跟踪每个 client 登记的 key 的上下线状态，
 * {@code ls} 回包里每条 KV 带一个 {@code offline} 标记，见 {@code KV#isOffline()}。
 * 所以这里只做一件事：**轮询并把在线的 KV 收进字典**，{@code offline=true} 的直接跳过 ——
 * 于是节点掉线会自然地表现为"下一轮它不在列表里"，日志上就是一条「下线」。
 *
 * <p>（早先这里配了一套 health check：协调器主动探每个节点的 {@code /health}、连续失败就删注册中心的
 * kv 并摘掉本地节点。注册中心自己能判上下线之后就多余了，那套代码已移除。）
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

    /** 四种节点 role。见 DESIGN.md §1.1 —— 这是一组封闭取值，所以写死在代码里而非配置。 */
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

    /** 列出某个 role 子树下的所有在线节点，按 shard-num / shard-id 两层归类。 */
    private Map<String, Map<String, String>> fetchNodes(String role) {
        List<KV> kvs = client.listAll(ROOT + "/" + role);
        if (kvs == null) {
            // listAll 把「路径不存在」和「请求失败」都表示成 null。走到这里说明连接是好的
            // （ensureConnected 已确认），所以按「该 role 当前一个节点都没有」处理。
            return Map.of();
        }

        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (KV kv : kvs) {
            // 注册中心跟踪每个 key 的上下线状态（KV#isOffline，服务端 1.0.5 起）。
            // 字典只收在线的 —— 掉线的节点不该出现在协调器的节点表里，于是它掉线后
            // 下一轮就不在列表里，日志上自然是一条「下线」。
            if (kv.isOffline()) {
                continue;
            }
            // ls 返回的是绝对路径，形如 cloud/{role}/{shard-num}/{shard-id}
            String[] parts = kv.getKey().split("/");
            if (parts.length != 4 || !ROOT.equals(parts[0]) || !role.equals(parts[1])) {
                log.warn("[{}] 这个键不是 {}/{}/{{shard-num}}/{{shard-id}} 的形状，已跳过：{}",
                        role, ROOT, role, kv.getKey());
                continue;
            }
            out.computeIfAbsent(parts[2], k -> new LinkedHashMap<>())
                    .put(parts[3], kv.getData() == null
                            ? "" : new String(kv.getData(), StandardCharsets.UTF_8));
        }
        return out;
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

    /**
     * 本节点与注册中心的那条长连接，给 {@link RoundStore} 读写索引轮次用。
     *
     * <p><b>每次都要现取，不要缓存这个引用。</b> {@link #ensureConnected()} 在掉线重连时会把
     * {@code client} 换成**另一个实例**，缓存下来的那个会一直指向已经 shutdown 的旧连接。
     *
     * <p>复用同一个实例是刻意的：{@code JRegistryClient} 内部有心跳与重连状态机，
     * 再新建一个会各维护一套连接、互相干扰（{@code cloud-indexer} 的 {@code RegistryRegistrar}
     * 里对同一件事有更长的说明）。
     *
     * @return 当前连接；从未连上（未配 registry-address 或一直连不上）时为 {@code null}
     */
    JRegistryClient client() {
        return client;
    }

    /**
     * 列出注册中心里某个路径下的键值对 —— {@link ConvergenceChecker} 靠它读
     * {@code cloud/index-ready/...}（那棵树不在本类轮询的四个 role 里）。
     *
     * <p><b>路径不带前导斜杠</b>，形如 {@code cloud/index-ready/vector-indexer/shard-0}。
     * <b>别忘了 {@code cloud/} 前缀</b> —— 少了它注册中心会静默返回空
     * （{@code ls} 回包 {@code success:false}，客户端变成一个空列表），
     * 表现是"检查在跑、却什么都不做、也不报错"。实机上撞到过。
     *
     * <p>返回的 KV 要**看 {@code isOffline()}** —— 过滤是调用方的事。
     *
     * @return 键值对；未连接、路径不存在、或请求失败时返回**空列表**（不是 null）
     */
    List<KV> listNodes(String path) {
        JRegistryClient c = this.client;
        if (c == null || !c.isConnected()) {
            return List.of();
        }
        List<KV> kvs = c.listAll(path);
        return kvs == null ? List.of() : kvs;
    }

    @Override
    public void destroy() {
        if (client != null) {
            client.shutdown();
        }
    }
}
