package hawk.cloud.coordinator;

import com.alibaba.fastjson.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 接收索引节点的完成通知：{@code POST /indexComplete}。
 *
 * <h2>请求体</h2>
 * <pre>
 * {
 *   "indexerRole": "text-indexer",
 *   "recallRole":  "text-recall",
 *   "shardNum":    "shard-0",
 *   "stamp":       "2026-10-14-10-52-49",
 *   "pushed":      ["id-0"],
 *   "failed":      ["id-1"]
 * }
 * </pre>
 * 由 cloud-indexer 在**索引建完并 commit、且推过一轮之后**调用（补推的回报走同一个端点）；
 * 协调器的地址它从 JRegistry 的 {@code cloud/coordinator} 下查到。
 *
 * <h2>收到之后做什么</h2>
 * 全部交给 {@link IndexCompletionTracker}：{@code failed} 为空就删掉该分片的记录；
 * 不空就记下来，由追踪器每 10 分钟催一轮 {@code /repush}，催满上限后放弃并告警（§7.2、§13.4）。
 * 本类只做参数校验与转发。
 *
 * <p>请求体按 fastjson 的 {@link JSONObject} 收（本模块 HTTP 层就是 fastjson），不另建请求 DTO。
 */
@RestController
public class IndexCompleteController {

    private static final Logger log = LoggerFactory.getLogger(IndexCompleteController.class);

    private final IndexCompletionTracker tracker;

    public IndexCompleteController(IndexCompletionTracker tracker) {
        this.tracker = tracker;
    }

    /**
     * 收到索引器的通知**或补推回报** —— 两者走同一个端点，所以这里不区分（方案 §7.2）。
     *
     * <p>payload 见方案 §12.5：**没有 indexDir**（那是索引器本机路径，协调器用不上）。
     */
    @PostMapping("/indexComplete")
    public ResponseEntity<?> indexComplete(@RequestBody JSONObject request) {
        String indexerRole = request.getString("indexerRole");
        String recallRole = request.getString("recallRole");
        String shardNum = request.getString("shardNum");
        String stamp = request.getString("stamp");

        if (isBlank(indexerRole) || isBlank(recallRole) || isBlank(shardNum) || isBlank(stamp)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "indexerRole / recallRole / shardNum / stamp 四个都不能为空"));
        }

        List<String> pushed = strings(request, "pushed");
        List<String> failed = strings(request, "failed");

        tracker.update(indexerRole, recallRole, shardNum, stamp, pushed, failed);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", failed.isEmpty() ? "complete" : "pending-repush");
        body.put("recallRole", recallRole);
        body.put("shardNum", shardNum);
        body.put("stamp", stamp);
        return ResponseEntity.ok(body);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static List<String> strings(JSONObject request, String key) {
        var arr = request.getJSONArray(key);
        return arr == null ? List.of() : arr.toJavaList(String.class);
    }

    /** 待补推的记录（监控用）。 */
    public Map<String, IndexCompletionTracker.Record> pending() {
        return tracker.pending();
    }
}
