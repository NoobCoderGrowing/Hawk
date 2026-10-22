package hawk.cloud.mcp;

import hawk.cloud.RegistryAddresses;
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
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TreeSet;

/**
 * 发现协调器地址。
 *
 * <h2>唯一来源：注册中心</h2>
 * 从 {@code cloud/coordinator} 拉全量，不读静态地址。协调器由 {@code RegistryRegistrar}
 * 登记，形如：
 * <pre>
 *   key   = /cloud/coordinator
 *   value = {ip}:{port}        ← 不带 scheme，这里补 http://
 * </pre>
 * 它不是分片，所以 key 只有两段；多副本时 {@code listAll} 会返回多条，
 * 它们都是等价入口（{@code /search} 无状态），全部收进候选。
 *
 * <h2>快照策略</h2>
 * 拉取失败、或注册中心里暂时一个在线的都没有时，**保留上一轮快照** ——
 * 协调器重启的窗口期内旧地址大概率仍然有效，清空只会让所有调用立刻失败。
 * 真正连不上某个地址时，由 {@link HawkSearchClient} 换下一个候选兜底。
 *
 * <p>路径必须写 {@code cloud/coordinator}（**不带前导斜杠、别漏 cloud/**）：
 * 少了前缀注册中心会静默返回空列表，表现是"轮询在跑、永远发现不到节点"。
 */
@Component
public class CoordinatorLocator implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorLocator.class);

    /** 注册中心里协调器的键（ls 返回的形式）。 */
    static final String COORDINATOR_PATH = "cloud/coordinator";

    private static final long TASK_TIMEOUT_MS = 3_000;
    private static final int CONNECT_TIMEOUT_MS = 3_000;

    @Value("${hawk.cloud.mcp.registry-address:}")
    private String registryAddress;

    /** 候选地址（{@code http://ip:port}）。整体替换，读侧不加锁。 */
    private volatile List<String> candidates = List.of();

    /** 长连接，跨轮询复用；掉线由 {@link #ensureConnected()} 重连。 */
    private JRegistryClient client;

    /** 两个来源都没配时只警告一次，不要每分钟刷一行。 */
    private boolean warnedUnconfigured;

    /**
     * 启动后先同步拉一次：否则第一个工具调用必然撞上空候选。
     *
     * <p>用 {@link ApplicationReadyEvent} 而不是 {@code @PostConstruct}，与三个 RegistryRegistrar
     * 保持一致；这里只是读注册中心，不依赖本进程的端口，先后顺序无所谓。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void discoverOnStartup() {
        refresh();
    }

    /**
     * 定时刷新。{@code initialDelay} 取一个周期：首次拉取交给
     * {@link #discoverOnStartup()}，这里从第二个周期开始。
     */
    @Scheduled(fixedDelayString = "${hawk.cloud.mcp.discovery-poll-ms:60000}",
               initialDelayString = "${hawk.cloud.mcp.discovery-poll-ms:60000}")
    public void refresh() {
        if (registryAddress == null || registryAddress.isBlank()) {
            if (!warnedUnconfigured) {
                log.warn("hawk.cloud.mcp.registry-address 为空，无法发现协调器");
                warnedUnconfigured = true;
            }
            return;
        }

        if (!ensureConnected()) {
            log.warn("连不上注册中心 {}，保留上一轮协调器快照：{}", registryAddress, candidates);
            return;
        }

        List<KV> kvs = client.listAll(COORDINATOR_PATH);
        if (kvs == null) {
            log.warn("注册中心里还没有 {}（协调器尚未登记？），保留上一轮快照：{}",
                    COORDINATOR_PATH, candidates);
            return;
        }

        TreeSet<String> fresh = new TreeSet<>();
        for (KV kv : kvs) {
            // 注册中心跟踪每个 key 的上下线；离线的不能当候选
            if (kv.isOffline()) {
                continue;
            }
            // ls 可能返回子树里的其它键；协调器只认精确的这一条
            if (!COORDINATOR_PATH.equals(kv.getKey())) {
                log.warn("{} 下出现非预期键，已跳过：{}", COORDINATOR_PATH, kv.getKey());
                continue;
            }
            if (kv.getData() == null || kv.getData().length == 0) {
                log.warn("协调器登记值为空，已跳过：{}", kv.getKey());
                continue;
            }
            fresh.add(normalize(new String(kv.getData(), StandardCharsets.UTF_8)));
        }

        if (fresh.isEmpty()) {
            log.warn("注册中心里没有在线的协调器，保留上一轮快照：{}", candidates);
            return;
        }

        List<String> updated = List.copyOf(fresh);
        if (!updated.equals(candidates)) {
            log.info("协调器地址更新：{} -> {}", candidates, updated);
            candidates = updated;
        }
    }

    /**
     * 当前候选地址。没有候选时返回空列表 —— 调用方据此给出可读错误，
     * 不要在这里抛异常（轮询线程会把它吞掉）。
     */
    public List<String> candidates() {
        return candidates;
    }

    /** 连上就复用，断开就按候选列表逐个重连。 */
    private boolean ensureConnected() {
        if (client != null && client.isConnected()) {
            return true;
        }
        if (client != null) {
            // 旧连接已死：先关掉，免得 NettyClient 自己的重连线程留着不放
            client.shutdown();
            client = null;
        }

        List<InetSocketAddress> addresses = RegistryAddresses.parse(registryAddress);
        if (addresses.isEmpty()) {
            log.error("registry-address 解析不出任何 host:port，实际为“{}”", registryAddress);
            return false;
        }
        for (InetSocketAddress address : addresses) {
            JRegistryClient c = new JRegistryClient(
                    address.getHostString(), address.getPort(), TASK_TIMEOUT_MS, CONNECT_TIMEOUT_MS);
            if (c.connect()) {
                client = c;
                log.info("已连上注册中心 {}", format(address));
                return true;
            }
            c.shutdown();
        }
        return false;
    }

    /** 补 scheme、去尾部斜杠。注册中心里存的是 {@code ip:port}，不能直接当 URL 用。 */
    static String normalize(String address) {
        String value = address.trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://" + value;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String format(SocketAddress address) {
        return address.toString().replaceFirst("^/", "");
    }

    @Override
    public void destroy() {
        if (client != null) {
            client.shutdown();
        }
    }
}
