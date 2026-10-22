package hawk.cloud.recall;

import io.github.noobcodergrowing.jregistryclient.JRegistryClient;
import io.github.noobcodergrowing.jregistrycore.KV;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 启动时向 JRegistry 注册中心登记本检索节点。
 *
 * <p>与 cloud-indexer 的 RegistryRegistrar 是同一套逻辑，只有配置前缀不同
 * （{@code hawk.cloud.recall.*} 而非 {@code hawk.cloud.indexer.*}）。
 *
 * <h2>登记内容</h2>
 * <pre>
 *   key   = /cloud/{role}/{shard-num}/{shard-id}      例：/cloud/recall/shard-1/id-0
 *   value = {ip}:{port}                               例：127.0.0.1:8092
 * </pre>
 * 占位符全部取自 {@code hawk-cloud.yml} 的 {@code hawk.cloud.recall.*}。
 * 节点地址放在 **value** 里，不是 key 的一段 —— key 只标识「哪个副本」。
 *
 * <p>两个编号含义不同，而且都是**字符串**、不是数字：
 * {@code shard-num} 是**第几个分片**（如 {@code shard-1}），
 * {@code shard-id} 是**该分片的副本编号**（如 {@code id-0}）——
 * 一个分片有多个副本，靠 shard-id 区分是哪一个。
 *
 * <h2>注册中心地址：单节点或集群节点表</h2>
 * {@code registry-address} 两种写法都认，见 {@link #parseRegistryAddresses}：
 * <pre>
 *   127.0.0.1:6001                                               单节点
 *   {1:'127.0.0.2:6001',2:'127.0.0.2:6002',3:'127.0.0.3:6003'}   集群节点表
 * </pre>
 * 节点表按 key 的数值升序逐个尝试连接，第一个连上的就用 —— JRegistryClient 只认一个
 * host:port，连哪个节点由它内部转发。
 *
 * <h2>失败不阻断启动</h2>
 * 没配 registry-address、地址格式不对、所有节点都连不上 —— 都只记日志后返回。
 * 本地开发不该为了跑起一个检索节点而先起注册中心。
 */
@Component
public class RegistryRegistrar implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RegistryRegistrar.class);

    /** 单次 RPC 等待上限（delete 会等回执）。 */
    private static final long TASK_TIMEOUT_MS = 3_000;

    /** 单节点的 TCP 建连超时。节点表里每个节点各算一次，所以取值别太大。 */
    private static final int CONNECT_TIMEOUT_MS = 3_000;

    /** value 的类型标记。值是纯地址文本，不是 JSON。 */
    private static final String VALUE_DATA_TYPE = "string";

    @Value("${hawk.cloud.recall.registry-address:}")
    private String registryAddress;

    @Value("${hawk.cloud.recall.role}")
    private String role;

    /** 第几个分片。路径里的一段名字（如 {@code shard-1}），不是数字。 */
    @Value("${hawk.cloud.recall.shard-num}")
    private String shardNum;

    /** 该分片的副本编号（如 {@code id-0}）。同一分片的多个副本靠它区分。 */
    @Value("${hawk.cloud.recall.shard-id}")
    private String shardId;

    @Value("${hawk.cloud.recall.ip}")
    private String ip;

    @Value("${hawk.cloud.recall.port}")
    private int port;

    /** 连上注册中心后才赋值；未登记时为 null，{@link #destroy()} 据此跳过注销。 */
    private JRegistryClient client;

    /** 本次登记的 key，注销时原样删除。 */
    private String key;

    /** 未配置 registry-address 时只警告一次，不要每次心跳都刷一行。 */
    private boolean warnedUnconfigured;

    /**
     * 首次登记，在 {@link ApplicationReadyEvent} 时 —— 比 {@code @PostConstruct} 晚，
     * 此时 web 服务已经监听端口，注册中心拿到的地址立刻可用。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        registerOnce(true);
    }

    /**
     * 心跳式重登记：每 {@code hawk.cloud.recall.register-interval-ms}（默认 60 秒）重发一次 set。
     *
     * <p>为什么需要：节点只在启动时登记一次，而这条 key 有可能被别人删掉 —— 最现实的一种是
     * cloud-coordinator 的健康检查。它连续探测失败到阈值时会删 key；如果失败计数恰好跨了节点
     * 的一次重启（重启后重新登记，但紧接着第 3 次探测仍失败），被删掉的就是一条**活**登记，
     * 而节点自己不会重登，于是它在注册中心里消失直到下次重启。定期重发把这类误删的存活窗口
     * 压到一个心跳周期内。
     *
     * <p>{@code initialDelay} 取与间隔相同的值：首次登记交给上面的 {@link #register()}，
     * 心跳从第二个周期才开始，免得在 HTTP 端口还没监听时就登记。
     */
    @Scheduled(fixedDelayString = "${hawk.cloud.recall.register-interval-ms:60000}",
               initialDelayString = "${hawk.cloud.recall.register-interval-ms:60000}")
    public void reRegister() {
        registerOnce(false);
    }

    /** 登记一次（必要时先连上/重连）。{@code startup} 只影响日志级别，其余行为一致。 */
    private void registerOnce(boolean startup) {
        if (registryAddress == null || registryAddress.isBlank()) {
            if (!warnedUnconfigured) {
                log.warn("hawk.cloud.recall.registry-address 未配置，跳过注册中心登记");
                warnedUnconfigured = true;
            }
            return;
        }

        if (key == null) {
            this.key = registrationKey(role, shardNum, shardId);
        }
        String address = nodeAddress();

        if (!ensureConnected()) {
            log.error("连不上注册中心，本节点未登记：{} -> {}", key, address);
            return;
        }

        // set() 是单向发送（NettyClient.sendRequest 不等回执），返回只代表已发出，
        // 不代表注册中心已落库 —— 所以这里写“已发送”而不是“已登记成功”。
        client.set(key, address.getBytes(StandardCharsets.UTF_8), VALUE_DATA_TYPE);
        if (startup) {
            log.info("已向注册中心发送登记：{} -> {}", key, address);
        } else {
            log.debug("心跳重登记：{} -> {}", key, address);
        }
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

        List<InetSocketAddress> candidates = parseRegistryAddresses(registryAddress);
        if (candidates.isEmpty()) {
            log.error("registry-address 解析不出任何 host:port，实际为“{}”", registryAddress);
            return false;
        }
        for (InetSocketAddress candidate : candidates) {
            JRegistryClient c = new JRegistryClient(
                    candidate.getHostString(), candidate.getPort(), TASK_TIMEOUT_MS, CONNECT_TIMEOUT_MS);
            if (c.connect()) {
                client = c;
                log.info("已连上注册中心 {}", candidate);
                return true;
            }
            // 连不上也要关掉：NettyClient 构造时就起了 EventLoopGroup 线程，
            // 不 shutdown 会留下非 daemon 线程，进程退不干净。
            c.shutdown();
        }
        log.error("注册中心候选节点全部连不上 {}", candidates);
        return false;
    }

    /**
     * 退出时尽力注销一次。
     *
     * <p>如实说明：这一步**多半发不出去**。JRegistryClient 在 {@code connectSync()} 里自己
     * 注册了一个 JVM shutdown hook（见 NettyClient.registerShutdownHookOnce），而 JVM 的多个
     * shutdown hook 是并发执行的、顺序不保证。实测通常它先关掉 channel，等这里执行时
     * {@code isConnected()} 已为 false，delete 被跳过 —— 注册中心里因此会留下一个死节点。
     *
     * <p>保留这段是为了那少数能赢下竞争的情况，成本为零。死节点的清理应当依赖注册中心侧的
     * 租约/心跳，不要依赖这里的 delete。
     */
    @Override
    public void destroy() {
        if (client == null) {
            return;
        }
        if (client.isConnected() && key != null) {
            client.delete(key);
            log.info("已向注册中心发送注销：{}", key);
        } else if (key != null) {
            log.debug("连接已被关闭，跳过注销：{}（见 destroy() 的说明）", key);
        }
        client.shutdown();
    }

    /** 拼 key：{@code /cloud/{role}/{shard-num}/{shard-id}}。静态以便单测。 */
    static String registrationKey(String role, String shardNum, String shardId) {
        return "/cloud/" + role + "/" + shardNum + "/" + shardId;
    }

    /** 登记值：本节点对外地址 {@code {ip}:{port}}。 */
    private String nodeAddress() {
        return ip + ":" + port;
    }

    // ------------------------------------------------- healthcheck 读取的状态

    /** 是否配了注册中心地址。没配属于正常（本地开发常没有注册中心），不算异常。 */
    public boolean isRegistryConfigured() {
        return registryAddress != null && !registryAddress.isBlank();
    }

    /** 现在是否仍连着注册中心 —— 登记那一刻连上、之后掉线都会反映在这里。 */
    public boolean isRegistered() {
        return client != null && client.isConnected();
    }

    /** 本节点角色。 */
    public String role() {
        return role;
    }

    /** 第几个分片（如 {@code shard-1}）。 */
    public String shardNum() {
        return shardNum;
    }

    /** 该分片的副本编号（如 {@code id-0}）。 */
    public String shardId() {
        return shardId;
    }

    /** 本节点身份 key：{@code /cloud/{role}/{shard-num}/{shard-id}}。 */
    public String nodeKey() {
        return registrationKey(role, shardNum, shardId);
    }

    /**
     * 列出注册中心里某个路径下的键值对 —— {@link IndexPullJob} 靠它找"哪些索引器有我需要的轮次"，
     * 以及那些索引器的地址。
     *
     * <p><b>路径不要带前导斜杠</b>（与 cloud-coordinator 的 NodeRegistry 一致），
     * 形如 {@code cloud/index-ready/vector-indexer/shard-0}。
     *
     * <p><b>复用本类已有的 {@link JRegistryClient}，不要新建第二个</b> ——
     * 它内部有心跳与重连状态机，两个实例各维护一套连接会互相干扰。
     *
     * <p>返回的 KV 要**看 {@code isOffline()}** —— 注册中心跟踪每个 key 的上下线状态，
     * 从一个已离线的节点拉索引只会白等超时。**过滤是调用方的事**（这里不做）。
     *
     * @return 该路径下的键值对；未连上注册中心、路径不存在、或请求失败时返回**空列表**（不是 null）
     */
    public List<KV> listNodes(String path) {
        JRegistryClient c = this.client;
        if (c == null || !c.isConnected()) {
            log.debug("注册中心未连接，列不出 {} 下的节点", path);
            return List.of();
        }
        List<KV> kvs = c.listAll(path);
        if (kvs == null) {
            // listAll 把"路径不存在"和"请求失败"都表示成 null —— 这两种分不开，只能当空
            return List.of();
        }
        return kvs;
    }

    /** 本节点对外地址，供 health 明细展示。 */
    public String address() {
        return nodeAddress();
    }

    /**
     * 从 {@code registry-address} 解析候选节点，返回顺序即尝试顺序。静态以便单测。
     *
     * <p>两种写法：
     * <pre>
     *   127.0.0.1:6001                                                单节点
     *   {1:'127.0.0.2:6001',2:'127.0.0.2:6002',3:'127.0.0.3:6003'}    集群节点表
     * </pre>
     * 节点表按 key 的数值升序排（1,2,3…）；key 不是数字的排在最后，保持出现顺序。
     *
     * <p>注意这里**不容错外层引号**：值应当直接是 {@code {1:'…'}}，外面再套一层
     * {@code "…"} 会让整体退化成「单节点」分支而解析失败。节点值本身的那对单引号是
     * 表语法的一部分，会被去掉。
     *
     * @return 候选节点；一个都解析不出时返回空列表
     */
    static List<InetSocketAddress> parseRegistryAddresses(String raw) {
        String value = raw.trim();

        List<Map.Entry<Long, String>> entries = new ArrayList<>();
        if (value.startsWith("{") && value.endsWith("}")) {
            for (String item : value.substring(1, value.length() - 1).split(",")) {
                int colon = item.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String hostPort = stripQuotes(item.substring(colon + 1).trim());
                long order;
                try {
                    order = Long.parseLong(item.substring(0, colon).trim());
                } catch (NumberFormatException e) {
                    order = Long.MAX_VALUE;   // 非数字 key：排到最后
                }
                entries.add(Map.entry(order, hostPort));
            }
            entries.sort(Comparator.comparingLong(Map.Entry::getKey));
        } else {
            entries.add(Map.entry(0L, value));
        }

        List<InetSocketAddress> out = new ArrayList<>();
        for (Map.Entry<Long, String> entry : entries) {
            InetSocketAddress address = asSocketAddress(entry.getValue());
            if (address != null) {
                out.add(address);
            } else {
                log.warn("registry-address 里这一项不是 host:port，已跳过：{}", entry.getValue());
            }
        }
        return out;
    }

    /** 解析单个 {@code host:port}；不合法返回 null。 */
    private static InetSocketAddress asSocketAddress(String hostPort) {
        int i = hostPort.lastIndexOf(':');
        if (i <= 0 || i == hostPort.length() - 1) {
            return null;
        }
        try {
            return new InetSocketAddress(hostPort.substring(0, i), Integer.parseInt(hostPort.substring(i + 1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 去掉节点表值外面成对的那层引号（单双都认）。 */
    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }
}
