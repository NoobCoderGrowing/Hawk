package hawk.cloud.coordinator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 定期探测四个字典里所有节点的 {@code GET /health}，连续失败到阈值就把节点判死：
 * 通知 JRegistry 删掉对应 kv，并从 {@link NodeRegistry} 的本地字典里摘掉。
 *
 * <h2>为什么需要它</h2>
 * 注册中心里的条目不会自己消失 —— 节点关停时的注销那条路是坏的（{@code delete} 在 shutdown hook
 * 竞争里发不出去），所以死节点会一直留在注册中心里。这个探测是目前唯一的存活判定手段。
 *
 * <h2>间隔怎么定的</h2>
 * 默认 15 秒 × 连续 3 次 = 最坏约 45 秒发现。更短会在建索引 / GC 的秒级停顿上误报；更长则没意义。
 * 它和 {@code poll-interval-ms}（60 秒，管成员与地址）是**独立**的两条时间线。
 *
 * <h2>并发与超时</h2>
 * 探测用 {@code HttpClient.sendAsync} 并发发出，用信号量把在飞请求数限制在
 * {@code health-concurrency} 以内 —— 否则一轮里挂掉一批节点时，单轮耗时会累加到超过间隔、
 * 轮次堆积。超时是硬性的，假死节点（TCP 能连但不响应）不会拖住整轮。
 */
@Component
public class NodeHealthChecker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(NodeHealthChecker.class);

    private final NodeRegistry nodes;

    public NodeHealthChecker(NodeRegistry nodes) {
        this.nodes = nodes;
    }

    @Value("${hawk.cloud.coordinator.health-timeout-ms:2000}")
    private long timeoutMs;

    @Value("${hawk.cloud.coordinator.health-fail-threshold:3}")
    private int failThreshold;

    @Value("${hawk.cloud.coordinator.health-concurrency:8}")
    private int concurrency;

    private HttpClient http;

    /**
     * 节点 key（{@code role/shard-num/shard-id}）-> 连续失败次数。
     * 只在失败时留痕，成功即清零；节点从字典里消失时也会被清掉（见 {@link #checkAll}），
     * 免得同一个 key 以后换了节点上来还背着旧的失败计数。
     */
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${hawk.cloud.coordinator.health-interval-ms:15000}")
    public void checkAll() {
        if (http == null) {
            // @Value 注入后第一次跑时才建 —— connectTimeout 依赖 timeoutMs。
            this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
        }

        List<NodeRegistry.NodeRef> targets = nodes.allNodes();
        Set<String> live = new HashSet<>();
        for (NodeRegistry.NodeRef target : targets) {
            live.add(keyOf(target));
        }
        consecutiveFailures.keySet().retainAll(live);   // 字典里已没有的，清掉计数

        if (targets.isEmpty()) {
            log.debug("没有可探测的节点");
            return;
        }

        Semaphore gate = new Semaphore(Math.max(1, concurrency));
        List<CompletableFuture<Void>> inFlight = new ArrayList<>(targets.size());
        for (NodeRegistry.NodeRef target : targets) {
            try {
                gate.acquire();   // 主线程取许可，保证同时在飞的请求不超过 concurrency
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            inFlight.add(probe(target)
                    .whenComplete((healthy, error) -> gate.release())
                    .thenAccept(healthy -> record(target, healthy)));
        }

        try {
            CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new)).join();
        } catch (Exception e) {
            log.warn("本轮健康检查未正常收尾：{}", e.getMessage());
        }
    }

    /** 探测一个节点；任何异常都算失败。 */
    private CompletableFuture<Boolean> probe(NodeRegistry.NodeRef target) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create("http://" + target.address() + "/health"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            // 地址本身不是合法 URI（比如 value 是空串）—— 直接算失败，不必发请求
            log.debug("[{}] 地址无法构成请求 {}：{}", target.role(), target.address(), e.getMessage());
            return CompletableFuture.completedFuture(false);
        }
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> response.statusCode() == 200
                        && response.body() != null
                        && response.body().contains("\"UP\""))
                .exceptionally(error -> {
                    log.debug("[{}] 探测 {} 失败：{}", target.role(), target.address(),
                            error.getMessage() == null ? error.toString() : error.getMessage());
                    return false;
                });
    }

    /** 记一次结果；连续失败到阈值就判死。 */
    private void record(NodeRegistry.NodeRef target, boolean healthy) {
        String key = keyOf(target);
        if (healthy) {
            Integer previous = consecutiveFailures.remove(key);
            if (previous != null) {
                log.info("[{}] 恢复健康 {}", target.role(), target.address());
            }
            return;
        }

        int failures = consecutiveFailures.merge(key, 1, Integer::sum);
        if (failures < failThreshold) {
            log.debug("[{}] 健康检查失败 {}/{}：{}", target.role(), failures, failThreshold, target.address());
            return;
        }

        consecutiveFailures.remove(key);
        log.warn("[{}] 连续 {} 次健康检查失败，从注册中心与本地字典移除：{}/{}/{} ({})",
                target.role(), failThreshold, target.role(), target.shardNum(), target.shardId(),
                target.address());
        nodes.condemn(target.role(), target.shardNum(), target.shardId());
    }

    private static String keyOf(NodeRegistry.NodeRef target) {
        return target.role() + "/" + target.shardNum() + "/" + target.shardId();
    }

    @Override
    public void destroy() {
        if (http != null) {
            http.close();   // JDK 21+：关掉 HttpClient 自己的线程，别拖着进程不退
        }
    }
}
