package hawk.cloud.recall;

import com.alibaba.fastjson.JSONObject;

import document.Document;
import field.Field;
import field.FieldType;
import hawk.recall.query.Query;
import hawk.recall.request.Condition;
import hawk.recall.search.ScoreDoc;
import hawk.recall.search.ScoreMode;
import hawk.recall.search.TopDocs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分片内的检索：{@code POST /search}。方案见 {@code DESIGN.md §5}。
 *
 * <pre>
 * POST /search
 * {
 *   "query": { "type":"text", "text":"冰糖", "fields":["title"],
 *              "fieldRelation":"SHOULD", "termRelation":"MUST" },
 *   "limit": 10,                    // 可选
 *   "fields": ["title","price"]     // 可选；不传 = 返回所有存储字段
 * }
 *
 * 200 {"totalHits":41,"totalExact":true,
 *      "hits":[{"pk":440,"score":8.12,"fields":{"title":"…","price":55.56}}]}
 *
 * 503 {"status":"no-index", …}     ← 本节点还没有索引，见下
 * 400 {"message": …}               ← 查询本身写错了（含不认识的 type / 关系）
 * </pre>
 *
 * <h2>契约边界：只保证「本分片的前 N」</h2>
 * 本端点返回的是**本分片按本分片分数排的前 N 条**。跨分片怎么归并、
 * 要不要重排序、N 该取多大 —— 全是调用方的事，这里不参与、也不做任何假设。
 * 分数只在分片内自洽（BM25 用的是本分片的 `totalDoc` / `avgDocumentLength`）。
 *
 * <h2>⚠️ 「还没拉到索引」必须回 503，不能回空结果</h2>
 * 拉取模型下"注册着、/health UP、但索引目录是空的"是**常态** —— 每个新起的副本都要经历这个阶段。
 * 如果这里返回 200 + 空结果，协调器就分不出「这一片还没就绪」和「这一片确实没匹配」，
 * 于是**悄悄少掉 1/分片数 的语料，而返回的 top-10 看起来完全正常**。
 * 所以没索引一律 **503**，协调器据此换一个副本重试。
 */
@RestController
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private final LocalSearcher local;

    /** 没传 {@code limit} 时用几条。 */
    @Value("${hawk.cloud.recall.search.default-limit:10}")
    private int defaultLimit;

    /**
     * {@code limit} 的上限。
     *
     * <p>`limit` 直接决定要物化多少条 {@code ScoreDoc}、以及取多少份存储字段 ——
     * 调用方写错一个零就是一次不小的分配。超了**夹住 + 记一条 warn**：
     * 不静默接受（会 OOM），也不报错（把调用方的一个笔误变成一次故障，不值）。
     */
    @Value("${hawk.cloud.recall.search.max-limit:1000}")
    private int maxLimit;

    public SearchController(LocalSearcher local) {
        this.local = local;
    }

    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody JSONObject request) {
        JSONObject rawQuery = request.getJSONObject("query");
        if (rawQuery == null) {
            return badRequest("缺少 query");
        }
        Condition condition;
        try {
            condition = ConditionJson.parse(rawQuery);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());          // 查询本身写错了，直接告诉调用方
        }

        int limit = resolveLimit(request.getInteger("limit"));
        Set<String> wanted = FieldsJson.resolve(request);

        try {
            Map<String, Object> body = local.query(snapshot -> {
                Query query = snapshot.compiler().compile(condition);
                TopDocs top = snapshot.searcher().search(query, limit, ScoreMode.TOP_SCORES);

                List<Map<String, Object>> hits = new ArrayList<>();
                for (ScoreDoc hit : top.scoreDocs()) {
                    Document doc = snapshot.searcher().doc(hit);
                    if (doc == null) {
                        continue;                        // 已被软删除（doc() 会返回 null）
                    }
                    Map<String, Object> hitJson = new LinkedHashMap<>();
                    hitJson.put("pk", snapshot.pkOf(hit.docId()));
                    hitJson.put("score", hit.score());
                    hitJson.put("fields", FieldsJson.render(doc, wanted));
                    hits.add(hitJson);
                }

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("totalHits", top.totalHits());
                // totalExact 是接口形状的一部分 —— 执行层提前终止时 totalHits 只是**下界**。
                // 丢掉它，调用方就会拿一个下界去算总页数，翻到后面全是空的而且看不出为什么。
                out.put("totalExact", top.totalExact());
                out.put("hits", hits);
                return out;
            });
            return ResponseEntity.ok(body);
        } catch (NoIndexException e) {
            // 见类注释：这条必须是 503，不能退化成一个空结果。
            // 它同时覆盖"还没拉到索引"和"索引打不开"两种 —— 该做的处置是同一件。
            log.info("收到检索请求，但本节点服务不了，回 503：{}", e.getMessage());
            return ResponseEntity.status(503).body(Map.of(
                    "status", "no-index",
                    "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            // RequestCompiler 在字段名写错时抛这个（schema 里没有那个字段）
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("检索失败", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    // ------------------------------------------------------------------

    private int resolveLimit(Integer requested) {
        if (requested == null) {
            return defaultLimit;
        }
        if (requested < 1) {
            return defaultLimit;                        // 0 或负数当没传 —— 不是错误，只是没意义
        }
        if (requested > maxLimit) {
            log.warn("limit={} 超过上限 {}，已夹住", requested, maxLimit);
            return maxLimit;
        }
        return requested;
    }

    private static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}
