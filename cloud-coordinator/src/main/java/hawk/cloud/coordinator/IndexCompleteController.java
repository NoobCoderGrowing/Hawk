package hawk.cloud.coordinator;

import com.alibaba.fastjson.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 接收索引节点的完成通知：{@code POST /indexComplete}。
 *
 * <h2>请求体</h2>
 * <pre>
 * {
 *   "role":     "text-indexer",
 *   "shardNum": "shard-0",
 *   "indexDir": "/data/index/text-indexer/shard-0/2026-10-13"
 * }
 * </pre>
 * 由 cloud-indexer / cloud-recall 在**索引建完并 commit 之后**调用；协调器的地址它们从
 * JRegistry 的 {@code cloud/coordinator} 下查到。
 *
 * <h2>目前只记下来</h2>
 * 收到就往内存里存成 {@code role -> (shardNum -> indexDir)} 并打日志。**还没有基于它做任何事**
 * —— 比如判断"某个 role 的所有分片是否都建好了"、或者调度检索节点去加载。要接的时候从
 * {@link #indexDirs()} 取。
 *
 * <p>请求体按 fastjson 的 {@link JSONObject} 收（本模块 HTTP 层就是 fastjson），不另建请求 DTO。
 */
@RestController
public class IndexCompleteController {

    private static final Logger log = LoggerFactory.getLogger(IndexCompleteController.class);

    /** role -> (shardNum -> 完整索引路径)。收到通知就更新。 */
    private final Map<String, Map<String, String>> indexDirs = new java.util.concurrent.ConcurrentHashMap<>();

    @PostMapping("/indexComplete")
    public ResponseEntity<?> indexComplete(@RequestBody JSONObject request) {
        String role = request.getString("role");
        String shardNum = request.getString("shardNum");
        String indexDir = request.getString("indexDir");

        if (role == null || role.isBlank() || shardNum == null || shardNum.isBlank()
                || indexDir == null || indexDir.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "role / shardNum / indexDir 三个都不能为空"));
        }

        indexDirs.computeIfAbsent(role, k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(shardNum, indexDir);
        log.info("索引完成通知：[{}] {} -> {}", role, shardNum, indexDir);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "recorded");
        body.put("role", role);
        body.put("shardNum", shardNum);
        body.put("indexDir", indexDir);
        return ResponseEntity.ok(body);
    }

    /** 收到过的通知：role -> (shardNum -> 索引路径)。 */
    public Map<String, Map<String, String>> indexDirs() {
        return indexDirs;
    }
}
