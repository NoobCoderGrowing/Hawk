package hawk.cloud.indexer;

import com.alibaba.fastjson.JSON;

import io.github.noobcodergrowing.jregistryclient.JRegistryClient;
import io.github.noobcodergrowing.jregistrycore.KV;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 索引建完之后通知协调器：哪个 role 的哪个分片建好了、索引在哪个目录。
 *
 * <h2>协调器的地址从哪来</h2>
 * **从注册中心查**，不在配置里写死：{@code listAll("cloud/coordinator")} 拿到它登记的
 * {@code ip:port}（协调器自己也走 {@code RegistryRegistrar} 登记，role 配成 {@code coordinator}）。
 * 地址解析直接复用 {@link RegistryRegistrar#parseRegistryAddresses}（同包，静态）。
 *
 * <h2>失败不影响索引</h2>
 * 通知是**尽力而为**：查不到协调器、连不上、对方返回非 200 —— 都只记日志。
 * 索引此时已经 commit 落盘了，不该因为通知失败而让整个请求失败。
 */
@Component
public class CoordinatorNotifier implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorNotifier.class);

    /** 协调器在注册中心里的路径 —— 与 cloud-coordinator 的 {@code role: coordinator} 对应。 */
    private static final String COORDINATOR_PATH = "cloud/coordinator";

    /** 注册中心 RPC 的等待上限。 */
    private static final long TASK_TIMEOUT_MS = 3_000;

    /** 建连超时，注册中心与 HTTP 各用一次。 */
    private static final int CONNECT_TIMEOUT_MS = 3_000;

    @Value("${hawk.cloud.indexer.registry-address:}")
    private String registryAddress;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .build();

    /**
     * 通知协调器：{@code role} 的 {@code shardNum} 分片索引建好了，路径是 {@code indexDir}。
     *
     * <p><b>不抛异常</b> —— 通知失败只记日志，见类注释。
     */
    public void indexComplete(String role, String shardNum, String indexDir) {
        if (registryAddress == null || registryAddress.isBlank()) {
            log.warn("hawk.cloud.indexer.registry-address 未配置，无从查协调器地址，跳过通知");
            return;
        }

        String coordinator = findCoordinator();
        if (coordinator == null) {
            log.warn("注册中心 {} 下没有协调器地址，跳过通知（{} {} -> {}）",
                    COORDINATOR_PATH, role, shardNum, indexDir);
            return;
        }

        try {
            String body = JSON.toJSONString(Map.of(
                    "role", role, "shardNum", shardNum, "indexDir", indexDir));
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://" + coordinator + "/indexComplete"))
                    .timeout(Duration.ofMillis(TASK_TIMEOUT_MS))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                log.info("已通知协调器 {}：{} {} -> {}", coordinator, role, shardNum, indexDir);
            } else {
                log.warn("协调器 {} 返回 {}：{}", coordinator, response.statusCode(), response.body());
            }
        } catch (Exception e) {
            // 索引已经 commit 了，通知失败不该影响它
            log.warn("通知协调器 {} 失败（索引已建好，不影响结果）：{}", coordinator, e.toString());
        }
    }

    /**
     * 从注册中心查协调器地址；查不到返回 {@code null}。
     *
     * <p>值就是 {@code ip:port} —— 登记时写进去的那个字符串，直接用。
     */
    private String findCoordinator() {
        List<InetSocketAddress> candidates = RegistryRegistrar.parseRegistryAddresses(registryAddress);
        for (InetSocketAddress candidate : candidates) {
            JRegistryClient client = new JRegistryClient(
                    candidate.getHostString(), candidate.getPort(), TASK_TIMEOUT_MS, CONNECT_TIMEOUT_MS);
            if (!client.connect()) {
                client.shutdown();
                continue;
            }
            try {
                List<KV> kvs = client.listAll(COORDINATOR_PATH);
                if (kvs == null || kvs.isEmpty()) {
                    return null;   // 连上了，但协调器还没登记
                }
                return new String(kvs.get(0).getData(), StandardCharsets.UTF_8);
            } finally {
                client.shutdown();
            }
        }
        return null;
    }

    @Override
    public void destroy() {
        http.close();   // JDK 21+：关掉 HttpClient 自己的线程
    }
}
