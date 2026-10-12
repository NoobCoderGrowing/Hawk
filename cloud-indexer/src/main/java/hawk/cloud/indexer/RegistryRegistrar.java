package hawk.cloud.indexer;

import io.github.noobcodergrowing.jregistryclient.JRegistryClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * 启动时向 JRegistry 注册中心登记本索引节点。
 *
 * <p>登记路径模板：
 * <pre>/cloud/{role}/{shard-num}/{shard-id}/{ip:port}</pre>
 * 占位符全部取自 {@code hawk-cloud-indexer.yml} 的 {@code hawk.cloud.indexer.*}；
 * 最后一段里的冒号是 ip 与 port 之间的分隔符，不属于占位符。
 * 例如 role=indexer、shard-num=1、shard-id=0、127.0.0.1:8091 会拼成
 * {@code /cloud/indexer/1/0/127.0.0.1:8091}。
 *
 * <h2>失败不阻断启动</h2>
 * 没配 registry-address、地址格式不对、连不上注册中心 —— 都只记日志后返回。
 * 本地开发不该为了跑起一个索引节点而先起注册中心。
 */
@Component
public class RegistryRegistrar implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(RegistryRegistrar.class);

    /** 单次 RPC 等待上限（delete 会等回执）。 */
    private static final long TASK_TIMEOUT_MS = 3_000;

    /** TCP 建连超时。 */
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    @Value("${hawk.cloud.indexer.registry-address:}")
    private String registryAddress;

    @Value("${hawk.cloud.indexer.role}")
    private String role;

    @Value("${hawk.cloud.indexer.shard-num}")
    private int shardNum;

    @Value("${hawk.cloud.indexer.shard-id}")
    private int shardId;

    @Value("${hawk.cloud.indexer.ip}")
    private String ip;

    @Value("${hawk.cloud.indexer.port}")
    private int port;

    /** 连上注册中心后才赋值；未登记时为 null，{@link #destroy()} 据此跳过注销。 */
    private JRegistryClient client;

    /** 本次登记的路径，注销时原样删除。 */
    private String key;

    /**
     * 在 {@link ApplicationReadyEvent} 时登记 —— 比 {@code @PostConstruct} 晚，
     * 此时 web 服务已经监听端口，注册中心/协调器拿到的地址立刻可用。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        if (registryAddress == null || registryAddress.isBlank()) {
            log.warn("hawk.cloud.indexer.registry-address 未配置，跳过注册中心登记");
            return;
        }

        InetSocketAddress registry = parseAddress(registryAddress);
        if (registry == null) {
            log.error("registry-address 应为 host:port，实际为“{}”，跳过登记", registryAddress);
            return;
        }

        this.key = registrationKey(role, shardNum, shardId, ip, port);

        JRegistryClient c = new JRegistryClient(
                registry.getHostString(), registry.getPort(), TASK_TIMEOUT_MS, CONNECT_TIMEOUT_MS);
        if (!c.connect()) {
            // 连不上也要关掉：NettyClient 构造时就起了 EventLoopGroup 线程，
            // 不 shutdown 会留下非 daemon 线程，进程退不干净。
            log.error("连接注册中心 {} 失败，本节点未登记：{}", registryAddress, key);
            c.shutdown();
            return;
        }

        // set() 是单向发送（NettyClient.sendRequest 不等回执），返回只代表已发出，
        // 不代表注册中心已落库 —— 所以这里写“已发送”而不是“已登记成功”。
        c.set(key, nodeDescriptor().getBytes(StandardCharsets.UTF_8), "json");
        this.client = c;
        log.info("已向注册中心 {} 发送登记：{}", registryAddress, key);
    }

    /** 优雅退出时先注销再断开，免得注册中心里留着一个已经死掉的节点。 */
    @Override
    public void destroy() {
        if (client == null) {
            return;
        }
        if (client.isConnected() && key != null) {
            client.delete(key);
            log.info("已向注册中心发送注销：{}", key);
        }
        client.shutdown();
    }

    /** 拼 {@code /cloud/{role}/{shard-num}/{shard-id}/{ip:port}}。静态以便单测。 */
    static String registrationKey(String role, int shardNum, int shardId, String ip, int port) {
        return "/cloud/" + role + "/" + shardNum + "/" + shardId + "/" + ip + ":" + port;
    }

    /** 节点自述，作为登记值写入。手拼 JSON，避免为一个对象再引一个序列化器。 */
    private String nodeDescriptor() {
        return "{\"role\":\"" + role + "\""
                + ",\"shardNum\":" + shardNum
                + ",\"shardId\":" + shardId
                + ",\"address\":\"" + ip + ":" + port + "\"}";
    }

    /** 解析 {@code host:port}；格式不合法返回 null。 */
    private static InetSocketAddress parseAddress(String address) {
        int i = address.lastIndexOf(':');
        if (i <= 0 || i == address.length() - 1) {
            return null;
        }
        try {
            return new InetSocketAddress(address.substring(0, i), Integer.parseInt(address.substring(i + 1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
