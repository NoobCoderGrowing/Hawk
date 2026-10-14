package hawk.cloud.coordinator;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 判「某个 role 的某个分片，有没有召回副本建成第 {@code expected} 轮」。
 * 方案见 {@code INDEX_BUILD_DISPATCH_DESIGN.md} §7.5。
 *
 * <h2>判据是「至少一个」</h2>
 * <b>有任意一个召回副本报的 round 等于 expected → 这一片就收敛了</b>，其余副本报什么都不管。
 * 这正是既有的弱一致语义（索引器推给所有在线副本，≥1 成功即成功）。把它写成「所有副本都必须是」
 * 会让每一次部分推送失败都同时触发 {@code /repush} 和一次**全量重建**
 * —— 代价从「重传 200 MB」涨到「重算 16 分钟」。
 *
 * <h2>为什么是「查召回侧」而不是「查索引器侧」</h2>
 * {@code pushToRecall} 里 {@code allFailed()} 时**不通知协调器**（"一个副本都没成功就不通知"），
 * 所以"整个分片一个副本都没推成功"这件事协调器**本来就不知道**。查召回侧才能把它捞出来：
 * 没有任何召回副本在服务 expected → 触发重下发。而"召回报 expected"严格强于"索引器报 expected"
 * （召回拿到它只可能是经索引器建成推过去的），所以查一侧就够。
 *
 * <h2>只看 /health，不加新端点</h2>
 * 两类节点的 {@code /health} 都在报自己的状态（召回端本来就在报 {@code freeBytes}）。
 * 再加一个端点等于多一条要维护的契约。
 */
@Component
public class ConvergenceChecker {

    private static final Logger log = LoggerFactory.getLogger(ConvergenceChecker.class);

    /** 一次 /health 的等待上限。局域网、只取一个小 JSON。 */
    private static final int CONNECT_TIMEOUT_MS = 2_000;

    private final NodeRegistry nodeRegistry;

    @Value("${hawk.cloud.coordinator.build.timeout-ms:5000}")
    private long healthTimeoutMs;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .build();

    public ConvergenceChecker(NodeRegistry nodeRegistry) {
        this.nodeRegistry = nodeRegistry;
    }

    /** 一个分片的判定结果。 */
    public record ShardResult(String shardNum, Verdict verdict, String detail) {
    }

    /**
     * 判定。**除了 {@link Verdict#CONVERGED} 之外都要重下发**，只有 {@link Verdict#AHEAD} 与
     * {@link Verdict#NO_REPLICA} 是"重下发也没用"的两种（见 §7.5 ③④）—— 调用方据此分开记日志。
     */
    public enum Verdict {
        /** 至少一个副本报的就是 expected。 */
        CONVERGED,
        /** 有副本可达，但没有一个报 expected（也没报更大的）→ 重下发。 */
        STALE,
        /** 该分片的副本**一个都联系不上** → 重下发。 */
        UNREACHABLE,
        /** 注册中心里该分片**没有在线召回副本** → 重下发没用，只告警。 */
        NO_REPLICA,
        /** 有副本报的轮次**比 expected 大** → 重下发会把更新的索引覆盖回旧的，只告警。 */
        AHEAD
    }

    /**
     * 判一个分片。
     *
     * @param indexerRole 索引器角色（{@code text-indexer} / {@code vector-indexer}）
     * @param shardNum    分片名，如 {@code shard-0}
     * @param expected    当前轮次
     */
    public ShardResult checkShard(String indexerRole, String shardNum, long expected) {
        Map<String, String> replicas = recallsOf(indexerRole).get(shardNum);
        if (replicas == null || replicas.isEmpty()) {
            return new ShardResult(shardNum, Verdict.NO_REPLICA, "注册中心里没有在线召回副本");
        }

        int reachable = 0;
        long maxSeen = -1;
        for (Map.Entry<String, String> replica : replicas.entrySet()) {
            Long round = fetchRound(replica.getValue());
            if (round == null) {
                // 「/health 取不到」和「那份索引里没有 round 文件（旁路调用建的）」在这里分不开。
                // 无所谓 —— 两种都不算数，都落到"这一片没收敛"。
                continue;
            }
            reachable++;
            if (round == expected) {
                return new ShardResult(shardNum, Verdict.CONVERGED,
                        replica.getKey() + " (" + replica.getValue() + ") 报 " + round);
            }
            maxSeen = Math.max(maxSeen, round);
        }

        if (reachable == 0) {
            return new ShardResult(shardNum, Verdict.UNREACHABLE,
                    replicas.size() + " 个副本的 /health 全都取不到");
        }
        if (maxSeen > expected) {
            return new ShardResult(shardNum, Verdict.AHEAD,
                    "有副本报的轮次比当前轮次更新（见到 " + maxSeen + "，当前 " + expected + "）");
        }
        return new ShardResult(shardNum, Verdict.STALE,
                reachable + " 个副本可达，但都没报 " + expected
                        + (maxSeen < 0 ? "（它们没有 round 信息）" : "（见到最大 " + maxSeen + "）"));
    }

    /** 所有分片都收敛才算这一轮成了。 */
    public boolean isConverged(String indexerRole, int shardCount, long expected) {
        for (int i = 0; i < shardCount; i++) {
            if (checkShard(indexerRole, "shard-" + i, expected).verdict() != Verdict.CONVERGED) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------

    /** 索引器角色 → 召回角色。显式映射，别用字符串替换（同 IndexCompletionTracker 的理由）。 */
    public static String recallRoleOf(String indexerRole) {
        return switch (indexerRole) {
            case IndexBuildDispatcher.ROLE_TEXT -> "text-recall";
            case IndexBuildDispatcher.ROLE_VECTOR -> "vector-recall";
            default -> throw new IllegalArgumentException("不认识的索引器角色：" + indexerRole);
        };
    }

    private Map<String, Map<String, String>> recallsOf(String indexerRole) {
        return switch (indexerRole) {
            case IndexBuildDispatcher.ROLE_TEXT -> nodeRegistry.textRecalls();
            case IndexBuildDispatcher.ROLE_VECTOR -> nodeRegistry.vectorRecalls();
            default -> throw new IllegalArgumentException("不认识的索引器角色：" + indexerRole);
        };
    }

    /**
     * 取一个召回副本「当前在服务的那一份索引」的轮次。
     *
     * @return 轮次；取不到（连不上 / 超时 / 那份索引没有 round 文件）→ {@code null}
     */
    private Long fetchRound(String address) {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://" + address + "/health"))
                    .timeout(Duration.ofMillis(healthTimeoutMs))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            JSONObject body = JSON.parseObject(response.body());
            // fastjson 默认省略 null —— 所以"那份索引没有 round"时这个键**根本不在**，
            // getLong 返回 null，正好就是我们要的语义。
            return body == null ? null : body.getLong("round");
        } catch (Exception e) {
            log.debug("取 {} 的 /health 失败：{}", address, e.toString());
            return null;
        }
    }
}
