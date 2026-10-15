package hawk.cloud.coordinator;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对外检索入口：{@code POST /search}。**多路召回 + 归并**。
 * 方案见 {@code CLOUD_RECALL_SEARCH_DESIGN.md}。
 *
 * <pre>
 * POST /search
 * {
 *   "query":       { … },           // 可选：**倒排**那一路的查询（Condition 树）
 *   "vectorQuery": "老黄冰糖",       // 可选：**向量**那一路的查询（一段文本）
 *   "limit":       10,              // 可选，默认 10
 *   "fields":      ["title"]        // 可选，转发给分片
 * }
 * </pre>
 * **给了哪一路就查哪一路**；两路都给就是多路召回（并发查，然后归并去重）。都不给 → 400。
 *
 * <h2>协调器仍然不认识查询语法</h2>
 * 它知道**有两路**（不然没法路由），但 `query` 里那个 Condition 树的形状、`vectorQuery`
 * 那段文本怎么被编码 —— 它一概不看，原样转发。以后 DSL 加条件类型、向量那边换模型，
 * **这里一行都不用改**。
 *
 * <h2>归并为什么要去重：跨路重复</h2>
 * 文档按 {@code floorMod(pk, shardCount)} 分片，**同一路内部不会重复**；
 * 但同一篇文档完全可能既被文本命中、又被向量命中。所以按 {@code pk} 去重，
 * 分数用 {@link SearchMerger} 里的 RRF 融合（BM25 与余弦不可比，只能按排名）。
 *
 * <h2>响应里必须能看出「结果可不可信」</h2>
 * <ul>
 *   <li>{@code totalExact}：**多路时一律 false** —— 两路各报的命中数相加会让公共文档算两遍，
 *       精确值要全量才知道，而我们只有各路的前 N 条；</li>
 *   <li>{@code failed}：哪些 {@code role/shard} 没回来、为什么。**部分结果照常返回，
 *       但绝不静默** —— 少了 1/N 的语料而 top-10 看起来正常，是最坏的一种失败。</li>
 * </ul>
 */
@RestController
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private final ShardSearchClient shardClient;

    /** 总分片数。与 {@code /index/build} 用同一个键 —— 两侧的分片规则必须一致。 */
    @Value("${hawk.cloud.coordinator.shard-count:3}")
    private int shardCount;

    @Value("${hawk.cloud.coordinator.search.default-limit:10}")
    private int defaultLimit;

    @Value("${hawk.cloud.coordinator.search.max-limit:1000}")
    private int maxLimit;

    public SearchController(ShardSearchClient shardClient) {
        this.shardClient = shardClient;
    }

    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody JSONObject request) {
        JSONObject query = request.getJSONObject("query");
        String vectorQuery = request.getString("vectorQuery");
        boolean hasText = query != null;
        boolean hasVector = vectorQuery != null && !vectorQuery.isBlank();
        if (!hasText && !hasVector) {
            return ResponseEntity.badRequest().body(Map.of("message",
                    "至少要给一路查询：query（倒排）或 vectorQuery（向量）"));
        }

        int limit = resolveLimit(request.getInteger("limit"));
        var fields = request.getJSONArray("fields");

        List<ShardSearchClient.RouteResult> routes = new ArrayList<>(2);
        if (hasText) {
            routes.add(shardClient.searchRoute(ShardSearchClient.ROLE_TEXT, shardCount,
                    textBody(query, limit, fields)));
        }
        if (hasVector) {
            routes.add(shardClient.searchRoute(ShardSearchClient.ROLE_VECTOR, shardCount,
                    vectorBody(vectorQuery, limit, fields)));
        }

        SearchMerger.Merged merged = SearchMerger.merge(routes, limit);

        List<Map<String, Object>> hits = new ArrayList<>(merged.hits().size());
        for (SearchMerger.Hit hit : merged.hits()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("pk", hit.pk());
            // 这个分数**不是** BM25 也不是余弦，是 RRF 融合分 —— 只能用来排序，不能当相关度读
            one.put("score", hit.score());
            // 被哪几路召回到的：只被一路命中 vs 两路都命中的差别，排查时很有用
            one.put("routes", hit.routes());
            one.put("fields", hit.fields());
            hits.add(one);
        }

        Map<String, Object> body = new LinkedHashMap<>(merged.routeSummary());
        body.put("hits", hits);
        log.info("检索完成：{}，返回 {}/{} 条", merged.routeSummary().get("routes"),
                hits.size(), limit);
        return ResponseEntity.ok(body);
    }

    // ------------------------------------------------------------------

    /** 倒排那一路的请求体：条件树 + 公共参数。 */
    private static JSONObject textBody(JSONObject query, int limit, JSONArray fields) {
        JSONObject body = new JSONObject();
        body.put("query", query);
        body.put("limit", limit);
        if (fields != null) {
            body.put("fields", fields);
        }
        return body;
    }

    /** 向量那一路：分片端把这段文本编码成向量（模型在它那儿，不在协调器）。 */
    private static JSONObject vectorBody(String vectorQuery, int limit, JSONArray fields) {
        JSONObject body = new JSONObject();
        body.put("query", vectorQuery);
        body.put("limit", limit);
        if (fields != null) {
            // fields 原样转发。分片那边如果这份索引没存原文，回的是空对象 + storedFields: []
            // —— 那是"索引没存"，调用方看得出，不需要协调器替它过滤
            body.put("fields", fields);
        }
        return body;
    }

    private int resolveLimit(Integer requested) {
        if (requested == null || requested < 1) {
            return defaultLimit;
        }
        if (requested > maxLimit) {
            log.warn("limit={} 超过上限 {}，已夹住", requested, maxLimit);
            return maxLimit;
        }
        return requested;
    }
}
