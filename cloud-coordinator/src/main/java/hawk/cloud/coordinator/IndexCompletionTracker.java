package hawk.cloud.coordinator;

import com.alibaba.fastjson.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录"哪些分片没推全"，并每 10 分钟催一次（方案 §7.2、§13.4）。
 *
 * <h2>弱一致</h2>
 * 索引器是"≥1 个副本成功就算成功"，所以这里只记**失败的副本**，
 * 补推直到全成功、或催满 {@code maxAttempts} 轮后放弃并告警。
 *
 * <h2>记录的键值</h2>
 * <pre>
 *   key   = {recallRole}/{shardNum}
 *   value = { stamp, pushed, failed, attempts }
 * </pre>
 * 键里**不含 stamp** —— 新的构建来了直接覆盖，这就是"丢弃旧记录"的天然实现（§12.5②）。
 *
 * <h2>两个不变量（别写反，错了都不报错）</h2>
 * <ol>
 *   <li><b>{@code attempts} 只在 {@code stamp} 变化时归零。</b>同一个 stamp 的**任何**更新
 *       （初次通知、补推回报）都不碰它 —— 否则"每轮失败 → 回报到达 → 归零"会循环，
 *       <b>永远到不了上限</b>（§15.1）</li>
 *   <li><b>{@code attempts++} 放在"发起一轮"时，不放在"收到回报"时。</b>
 *       这样**不依赖回报是否到达** —— 回报丢了也照样计数、照样会放弃</li>
 * </ol>
 *
 * <h2>内存态</h2>
 * 协调器重启即丢。丢了就等下次重建（重建会推给所有在线副本，自然补平）。
 */
@Service
public class IndexCompletionTracker {

    private static final Logger log = LoggerFactory.getLogger(IndexCompletionTracker.class);

    /** 一条待补推的记录。 */
    public record Record(String stamp, List<String> pushed, List<String> failed, int attempts) {
    }

    private final NodeRegistry nodeRegistry;

    /** 补推轮次上限。到顶就删记录 + 告警（§13.4）。 */
    @Value("${hawk.cloud.coordinator.max-attempts:3}")
    private int maxAttempts;

    @Value("${hawk.cloud.coordinator.retry-timeout-ms:5000}")
    private long retryTimeoutMs;

    private final Map<String, Record> records = new ConcurrentHashMap<>();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public IndexCompletionTracker(NodeRegistry nodeRegistry) {
        this.nodeRegistry = nodeRegistry;
    }

    /** 收到索引器的通知或补推回报。两处走同一个入口 —— 所以不必区分它们。 */
    public synchronized void update(String indexerRole, String recallRole, String shardNum,
                                    String stamp, List<String> pushed, List<String> failed) {
        String key = key(recallRole, shardNum);
        Record old = records.get(key);
        // ★ 不变量 1：只有 stamp 变了才把 attempts 归零
        int attempts = (old != null && old.stamp().equals(stamp)) ? old.attempts() : 0;

        if (failed.isEmpty()) {
            records.remove(key);
            log.info("[{}] {} stamp={} 副本已齐，删掉补推记录", recallRole, shardNum, stamp);
            return;
        }
        records.put(key, new Record(stamp, pushed, failed, attempts));
        log.info("[{}] {} stamp={} 待补推的副本：{}（已催 {} 轮）",
                recallRole, shardNum, stamp, failed, attempts);
    }

    /** 当前待补推的记录（监控用）。 */
    public Map<String, Record> pending() {
        return Map.copyOf(records);
    }

    /**
     * 每 10 分钟催一轮。**串行处理记录**（§13.6）—— {@code /repush} 是快速返回的
     * （索引器只抢锁 + 后台推），所以一轮是毫秒级，不会和下一轮重叠。
     */
    @Scheduled(fixedDelayString = "${hawk.cloud.coordinator.retry-interval-ms:600000}",
               initialDelayString = "${hawk.cloud.coordinator.retry-interval-ms:600000}")
    public void retryRound() {
        for (Map.Entry<String, Record> e : records.entrySet()) {
            String[] parts = e.getKey().split("/", 2);
            retryOne(parts[0], parts[1], e.getValue());
        }
    }

    private void retryOne(String recallRole, String shardNum, Record rec) {
        String key = key(recallRole, shardNum);

        // ★ 不变量 2：在"发起一轮"时自增，不在"收到回报"时
        if (rec.attempts() >= maxAttempts) {
            records.remove(key);
            log.error("[{}] {} stamp={} 补推 {} 轮仍有副本没推成 {} —— 放弃，"
                            + "这些副本要保持陈旧直到下一次重建",
                    recallRole, shardNum, rec.stamp(), maxAttempts, rec.failed());
            return;
        }

        String indexerRole = indexerRoleOf(recallRole);
        String indexer = findIndexer(indexerRole, shardNum);
        if (indexer == null) {
            log.warn("[{}] {} 找不到在线的索引器（{}），这一轮跳过", recallRole, shardNum, indexerRole);
            return;
        }

        records.put(key, new Record(rec.stamp(), rec.pushed(), rec.failed(), rec.attempts() + 1));
        String url = "http://" + indexer + "/repush"
                + "?role=" + recallRole + "&shard=" + shardNum + "&stamp=" + rec.stamp()
                + "&only=" + String.join(",", rec.failed());
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(retryTimeoutMs))     // 只等一个受理回执，秒级（§12.8）
                    .POST(HttpRequest.BodyPublishers.ofString("", StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            switch (response.statusCode()) {
                case 200, 202 -> log.info("[{}] {} 已催第 {} 轮：{}", recallRole, shardNum,
                        rec.attempts() + 1, response.body());
                case 409 -> log.info("[{}] {} 索引器正忙，这一轮不算数", recallRole, shardNum);
                case 410 -> {
                    // 索引器本地没这个 stamp 了 —— 放弃这条记录（§12.5⑤）
                    records.remove(key);
                    log.error("[{}] {} stamp={} 索引器本地已无此 stamp，放弃补推", recallRole, shardNum, rec.stamp());
                }
                default -> log.warn("[{}] {} /repush 返回 {}：{}",
                        recallRole, shardNum, response.statusCode(), response.body());
            }
        } catch (Exception ex) {
            log.warn("[{}] {} 催补推失败（下一轮再来）：{}", recallRole, shardNum, ex.toString());
        }
    }

    /** 索引器与召回角色是**配对**的（方案 §2.1）。显式映射，别用字符串替换。 */
    private static String indexerRoleOf(String recallRole) {
        return switch (recallRole) {
            case "text-recall" -> "text-indexer";
            case "vector-recall" -> "vector-indexer";
            default -> null;
        };
    }

    /** 从 NodeRegistry 的字典里找该分片的一个在线索引器。 */
    private String findIndexer(String indexerRole, String shardNum) {
        Map<String, Map<String, String>> dict =
                "vector-indexer".equals(indexerRole) ? nodeRegistry.vectorIndexers() : nodeRegistry.textIndexers();
        Map<String, String> replicas = dict.get(shardNum);
        if (replicas == null || replicas.isEmpty()) {
            return null;
        }
        return replicas.values().iterator().next();      // 同分片的副本等价，取一个即可
    }

    private static String key(String recallRole, String shardNum) {
        return recallRole + "/" + shardNum;
    }

    /** 给监控端点用。 */
    public String describe() {
        return JSON.toJSONString(records);
    }
}
