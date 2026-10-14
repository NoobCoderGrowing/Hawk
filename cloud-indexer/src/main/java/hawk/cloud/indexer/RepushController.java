package hawk.cloud.indexer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 补推端点：{@code POST /repush}。协调器每 10 分钟来催一次（方案 §7.2）。
 *
 * <pre>
 * POST /repush?role=vector-recall&amp;shard=shard-0&amp;stamp=2026-10-14-10-52-49&amp;only=id-2,id-3
 * </pre>
 *
 * <h2>重推 ≠ 重建</h2>
 * 按 {@code stamp} 找**本地已经建好的那份目录**直接重推，不重新编码。重新跑一遍会生成
 * **新的 stamp、新的索引** —— 与已经推给其他副本的那份不是同一个东西。
 *
 * <h2>只拿 Lock2</h2>
 * 补推不建索引，所以它不碰 Lock1（全阶段）—— 它能在**构建的编码阶段**旁边跑。
 * 但与"推送/清理"互斥（清理会删掉它正在读的目录），所以走 Lock2。
 *
 * <h2>响应码</h2>
 * <table border="1">
 *   <tr><th>码</th><th>含义</th><th>协调器</th></tr>
 *   <tr><td>202</td><td>已受理，<b>推在后台跑</b></td><td>等 /indexComplete 回调，不另处理</td></tr>
 *   <tr><td>409</td><td>本节点正忙（Lock2 被占）</td><td>下一轮再来</td></tr>
 *   <tr><td>410</td><td>本地没有这个 stamp（被保留策略清了）</td><td><b>删记录 + 告警</b></td></tr>
 *   <tr><td>400</td><td>参数不对（含 stamp 格式）</td><td>删记录 + 告警（不该发生）</td></tr>
 * </table>
 */
@RestController
public class RepushController {

    private static final Logger log = LoggerFactory.getLogger(RepushController.class);

    private final IndexService textService;
    private final VectorIndexService vectorService;

    /** 本节点角色 —— 只服务与之配对的那一类召回角色（{@code text-indexer} → {@code text-recall}）。 */
    @Value("${hawk.cloud.indexer.role}")
    private String indexerRole;

    @Value("${hawk.cloud.indexer.shard-num}")
    private String shardNum;

    public RepushController(IndexService textService, VectorIndexService vectorService) {
        this.textService = textService;
        this.vectorService = vectorService;
    }

    @PostMapping("/repush")
    public ResponseEntity<?> repush(@RequestParam(name = "role") String recallRole,
                                    @RequestParam(name = "shard") String shard,
                                    @RequestParam(name = "stamp") String stamp,
                                    @RequestParam(name = "only", required = false) String only) {
        // stamp 会被**当成目录名**拼进本地路径，所以和召回端一样是安全边界，不是格式偏好。
        // 不校验的话 "../../etc/foo" 会在索引根之外找目录（§12.5④）
        if (!IndexStamp.isValid(stamp)) {
            return bad("stamp 格式不对：" + stamp);
        }
        if (!this.shardNum.equals(shard)) {
            return bad("shard 不匹配：本节点是 " + this.shardNum + "，请求是 " + shard);
        }
        List<String> onlyList = (only == null || only.isBlank())
                ? null : Arrays.stream(only.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();

        IndexPusher.RepushOutcome outcome;
        if ("text-recall".equals(recallRole) && "text-indexer".equals(indexerRole)) {
            outcome = textService.repush(stamp, onlyList);
        } else if ("vector-recall".equals(recallRole) && "vector-indexer".equals(indexerRole)) {
            outcome = vectorService.repush(stamp, onlyList);
        } else {
            // 与本节点配对不上 —— 和召回端的角色校验同理，别照收
            return bad("本节点是 " + indexerRole + "，不服务 " + recallRole);
        }

        return switch (outcome.status()) {
            case BUSY -> {
                log.info("收到 /repush 但本节点正忙（Lock2 被占），回 409");
                yield ResponseEntity.status(409).body(Map.of("status", "busy",
                        "message", "本节点正在推送/清理，稍后再试"));
            }
            case GONE -> {
                log.warn("收到 /repush 但本地没有 stamp={}（已被保留策略清掉），回 410", stamp);
                yield ResponseEntity.status(410).body(Map.of("status", "gone",
                        "message", "本地没有这个 stamp，请放弃这条记录"));
            }
            case OK -> ResponseEntity.accepted().body(Map.of(
                    // **推在后台跑**，结果走 /indexComplete 回调 —— 这里只回一个"已受理"
                    "status", "accepted", "stamp", stamp));
        };
    }

    private static ResponseEntity<Map<String, String>> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}
