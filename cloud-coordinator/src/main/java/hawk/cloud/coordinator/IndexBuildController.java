package hawk.cloud.coordinator;

import com.alibaba.fastjson.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 发起索引构建：{@code POST /index/build}。方案见 {@code INDEX_BUILD_DISPATCH_DESIGN.md} §3。
 *
 * <pre>
 * POST /index/build
 * {
 *   "role": "vector-indexer",        // 必填：text-indexer | vector-indexer
 *   "shardCount": 3,                 // 可选，缺省用 hawk.cloud.coordinator.shard-count
 *   "fieldList": [ ... ],            // 以下全部**原样转发**给索引器，协调器一个字段都不看
 *   "vectorFields": ["title"],
 *   "device": "cpu"
 * }
 * </pre>
 *
 * <h2>本类只做三件事</h2>
 * <ol>
 *   <li>校验 {@code role} 与 {@code shardCount}（协调器**必须**知道这两个，它要靠它们决定循环几次）；
 *       其余字段不解析 —— 那是索引器的 {@code IndexApi#parse}（cloud-indexer 模块）的职责，
 *       在这里抄一份就是第三份拷贝（而且那个方法跨模块、跨包都够不着）。
 *       两个索引端点的入参与响应**逐字同形**，所以下面转发出去的 body 对两者都是合法的。</li>
 *   <li>调 {@link IndexBuildDispatcher}；</li>
 *   <li>把 {@code DispatchResult.Status} 映射成状态码。</li>
 * </ol>
 *
 * <h2>202 是「受理」，不是「建好了」</h2>
 * 收到 202 只代表请求已经派给索引器了。真正的构建是**分钟级**的，而且索引器建完还要推给召回端 ——
 * 想确认建好了得看 round tracker 的收敛状态，不是这个响应。调用方最容易犯的错就是拿它当"索引可用了"。
 */
@RestController
public class IndexBuildController {

    private static final Logger log = LoggerFactory.getLogger(IndexBuildController.class);

    private final IndexBuildDispatcher dispatcher;

    /** {@code shardCount} 的缺省值。请求体里给了就用请求体的。 */
    @Value("${hawk.cloud.coordinator.shard-count:3}")
    private int defaultShardCount;

    public IndexBuildController(IndexBuildDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @PostMapping("/index/build")
    public ResponseEntity<?> build(@RequestBody JSONObject request) {
        String role = request.getString("role");
        if (role == null || !IndexBuildDispatcher.supports(role)) {
            return badRequest("role 只能是 text-indexer 或 vector-indexer，收到 " + role);
        }

        Integer shardCount = request.getInteger("shardCount");
        if (shardCount == null) {
            shardCount = defaultShardCount;
        }
        if (shardCount < 1) {
            return badRequest("shardCount 必须 ≥ 1，收到 " + shardCount);
        }

        // role 是协调器自己的概念（决定打哪个端点、查哪张节点表），索引器不认它 —— 去掉再转发，
        // 免得让索引器的日志里出现一个它没有的字段。shardCount 则要显式补上（可能来自配置）。
        JSONObject forwarded = new JSONObject(request);
        forwarded.remove("role");
        forwarded.put("shardCount", shardCount);

        IndexBuildDispatcher.DispatchResult result = dispatcher.dispatch(role, shardCount, forwarded);

        return switch (result.status()) {
            case DISPATCHED, PARTIAL -> ResponseEntity.accepted().body(body(result));
            case BAD_REQUEST -> ResponseEntity.badRequest().body(Map.of(
                    "message", "索引器拒绝了请求体：" + result.message()));
            case BUSY -> ResponseEntity.status(409).body(Map.of(
                    "status", "building",
                    "message", "上一次派发还没结束，稍后再试"));
            // 上一轮还没收敛 —— 也是 409，但和 BUILDING 是两回事：那个是"几毫秒后就好"，
            // 这个是"要等一轮构建"（分钟级），调用方该等就该等很久。
            case UNCONVERGED -> ResponseEntity.status(409).body(Map.of(
                    "status", "previous-round-unconverged",
                    "round", result.round(),
                    "message", result.message()));
            case REJECTED -> ResponseEntity.status(503).body(body(result));
        };
    }

    private static Map<String, Object> body(IndexBuildDispatcher.DispatchResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", switch (result.status()) {
            case DISPATCHED -> "dispatched";
            case PARTIAL -> "partial";
            case REJECTED -> "rejected";
            default -> "unknown";
        });
        out.put("role", result.indexerRole());
        out.put("round", result.round());
        out.put("shardCount", result.shardCount());
        out.put("dispatched", result.dispatched());
        out.put("failed", result.failed());
        return out;
    }

    private static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}
