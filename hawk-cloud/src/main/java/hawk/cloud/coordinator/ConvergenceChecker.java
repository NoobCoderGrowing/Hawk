package hawk.cloud.coordinator;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import io.github.noobcodergrowing.jregistrycore.KV;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 判「某个 role 的某个分片，有没有索引器副本建成了第 {@code expected} 轮」。
 * 方案见 {@code INDEX_PULL_MODEL_DESIGN.md} §3.4。
 *
 * <h2>为什么读注册中心，而不是去问召回端</h2>
 * 拉取模型里索引器建完会登记「我这儿有第 R 轮」，那**就是**权威答案。
 * 以前要逐个召回副本 {@code GET /health} 读 round（N 个分片 × 副本数次 HTTP），
 * 现在只要一次 {@code listAll}。而且判据的口径也跟着变了：
 * <ul>
 *   <li>以前：「**召回端**在服务 R」—— 端到端，但索引器一个副本都没推成功时协调器**收不到通知**，
 *       那个盲区要靠查召回侧才捞得出来；</li>
 *   <li>现在：「**索引器**建成了 R」—— 召回端什么时候拉到，由它自己的定时任务负责，不在这里跟踪。</li>
 * </ul>
 * 后者是**刻意的放宽**（拉取端本来就设计成自愈的），换来的是判据从"发 N 个 HTTP"变成"读一次注册中心"。
 *
 * <h2>判据是「至少一个」</h2>
 * 有任意一个副本登记了 {@code expected} 就算这一片成了。这正是既有的弱一致语义
 * （谁建的都一样，内容等价）。写成"所有副本都必须"会让每次部分落后都触发一次**全量重建**。
 */
@Component
public class ConvergenceChecker {

    private static final Logger log = LoggerFactory.getLogger(ConvergenceChecker.class);

    /**
     * 索引器"我这儿有第 R 轮"的登记路径根。
     * <b>必须与 indexer 侧 {@code IndexReadyRegistry}、recall 侧 {@code IndexPullJob} 三处一致。</b>
     */
    private static final String READY_ROOT = "cloud/index-ready";

    private final NodeRegistry nodeRegistry;

    public ConvergenceChecker(NodeRegistry nodeRegistry) {
        this.nodeRegistry = nodeRegistry;
    }

    /** 一个分片的判定结果。 */
    public record ShardResult(String shardNum, Verdict verdict, String detail) {
    }

    /**
     * <b>只有 {@link Verdict#STALE} 值得重下发。</b>
     * 另外三个要么已经成了，要么重下发也修不好（见各自说明）——
     * 把它们也当成"该重下发"会让每轮白派一次，而且日志里看不出是白派的。
     */
    public enum Verdict {
        /** 至少一个副本登记的就是 expected。 */
        CONVERGED,
        /** 有在线副本，但没有一个登记 expected（也没登记更大的）→ **重下发**。 */
        STALE,
        /** 有副本登记的轮次**比 expected 大** → 不重下发，只告警：那说明本协调器手里的轮次反而旧了，
         *  重下发会把更新的索引覆盖回旧的。 */
        AHEAD,
        /** 该分片**没有在线的索引器副本** → 重下发也没用（一个字节都发不出去），只告警。 */
        NO_REPLICA
    }

    /**
     * 判一个分片。
     *
     * @param indexerRole {@code text-indexer} / {@code vector-indexer}
     * @param shardNum    分片名，如 {@code shard-0}
     * @param expected    当前轮次
     */
    public ShardResult checkShard(String indexerRole, String shardNum, long expected) {
        List<KV> kvs = nodeRegistry.listNodes(READY_ROOT + "/" + indexerRole + "/" + shardNum);

        int online = 0;
        long maxSeen = -1;
        for (KV kv : kvs) {
            if (kv.isOffline()) {
                continue;                        // 登记的客户端都离线了，问不到
            }
            online++;
            Long round = parseRound(kv.getData(), kv.getKey());
            if (round == null) {
                continue;
            }
            if (round == expected) {
                return new ShardResult(shardNum, Verdict.CONVERGED,
                        kv.getKey() + " 报 " + round);
            }
            maxSeen = Math.max(maxSeen, round);
        }

        if (online == 0) {
            return new ShardResult(shardNum, Verdict.NO_REPLICA,
                    kvs.isEmpty() ? "注册中心里这一片没有登记" : kvs.size() + " 条登记全部离线");
        }
        if (maxSeen > expected) {
            return new ShardResult(shardNum, Verdict.AHEAD,
                    "有副本登记的轮次比当前更新（见到 " + maxSeen + "，当前 " + expected + "）");
        }
        return new ShardResult(shardNum, Verdict.STALE,
                online + " 个在线副本，但没有一个登记 " + expected
                        + (maxSeen < 0 ? "（它们的登记里没有 round）" : "（见到最大 " + maxSeen + "）"));
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

    /** 解析 {@code {"round":11,"stamp":"..."}}；形状不对返回 null。 */
    private static Long parseRound(byte[] data, String key) {
        if (data == null) {
            return null;
        }
        try {
            JSONObject json = JSON.parseObject(new String(data, StandardCharsets.UTF_8));
            Long round = json == null ? null : json.getLong("round");
            if (round == null) {
                log.warn("{} 的登记里没有 round，跳过：{}", key, json);
            }
            return round;
        } catch (RuntimeException e) {
            log.warn("{} 的登记解析失败，跳过：{}", key, e.toString());
            return null;
        }
    }
}
