package hawk.cloud.recall;

import com.alibaba.fastjson.JSONObject;

import hawk.vector.search.VectorHit;

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
import java.util.Set;

/**
 * 分片内的**向量**检索：{@code POST /vectorSearch}。
 * 方案见 {@code CLOUD_RECALL_SEARCH_DESIGN.md}（文本侧的对照物是 {@link SearchController}）。
 *
 * <pre>
 * POST /vectorSearch
 * {
 *   "query": "老黄冰糖",     // 查询**文本**；本节点用自己那份模型编码成向量
 *   "limit": 10,             // 可选
 *   "fields": ["title"]      // 可选；不传 = 返回索引里存的所有字段
 * }
 *
 * 200 {"totalHits":10,"totalExact":true,
 *      "hits":[{"pk":49732,"score":0.8405,"fields":{"title":"…"}}]}
 *
 * 503 {"status":"no-index", …}    ← 本节点服务不了这个版本 —— 没索引，**或模型对不上/索引打不开**
 * 400 {"message": …}              ← query 缺失 / 空
 * </pre>
 *
 * <h2>契约边界同文本侧：只保证「本分片的前 N」</h2>
 * 跨分片怎么归并、要不要重排序、N 取多大 —— 全是调用方的事。
 *
 * <p><b>但有一处比文本侧更省心</b>：余弦相似度**不需要语料统计量**，
 * 所以不同分片算出来的分数**天然同量纲、可以直接比** —— 文本那边的
 * "分片局部 IDF 导致分数不可比"（设计 §5）在这里**不存在**。
 *
 * <h2>{@code fields} 从哪来</h2>
 * 向量图的文件里**没有原文的位置** —— 只装得下向量和 HNSW 邻接表。
 * 所以原文是**另存的一份**（{@code stored.fdt/.fdx/.fdm}，见 {@code hawk.vector.stored}），
 * 由建索引时请求里 {@code isStored=YES} 的字段决定。
 *
 * <p>于是：<b>这份索引建的时候有没有存原文，决定这个端点回不回得了 {@code fields}</b>。
 * 没配存储字段的索引，{@code fields} 就是个空对象 —— 那是"索引没存"，不是"字段是空的"。
 * 要确认到底存了哪些，看 {@link #search} 响应里的 {@code storedFields}。
 *
 * <h2>查询文本在哪编码：**在本节点，不在协调器**</h2>
 * 每个分片各自把同一个查询串编码一遍 —— N 个分片就编 N 次。这是刻意的取舍：
 * <ul>
 *   <li><b>好</b>：协调器保持轻量（不用装 ONNX 模型、不用管模型版本），
 *       而它"对所有查询细节零知识"这条边界也就守住了；</li>
 *   <li><b>代价</b>：N 倍重复计算。不过编码一次是毫秒级、而且 N 个分片是**并行**的，
 *       墙钟时间几乎不变。</li>
 * </ul>
 * 反过来让协调器编码，就得把模型塞进协调器 —— 那它就从"轻量路由器"变成一个要管模型版本的服务了。
 */
@RestController
public class VectorSearchController {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchController.class);

    private final LocalVectorSearcher local;

    @Value("${hawk.cloud.recall.search.default-limit:10}")
    private int defaultLimit;

    /** 同文本侧：夹住而不是报错，也不静默接受。见 {@link SearchController} 上那段说明。 */
    @Value("${hawk.cloud.recall.search.max-limit:1000}")
    private int maxLimit;

    public VectorSearchController(LocalVectorSearcher local) {
        this.local = local;
    }

    @PostMapping("/vectorSearch")
    public ResponseEntity<?> search(@RequestBody JSONObject request) {
        String query = request.getString("query");
        if (query == null || query.isBlank()) {
            return badRequest("缺少 query（向量检索的 query 是一段文本，本节点自己编码成向量）");
        }
        int limit = resolveLimit(request.getInteger("limit"));
        Set<String> wanted = FieldsJson.resolve(request);

        try {
            Map<String, Object> body = local.query(snapshot -> {
                VectorHit[] hits = snapshot.searcher().search(query, limit);

                List<Map<String, Object>> hitJson = new ArrayList<>(hits.length);
                for (VectorHit hit : hits) {
                    Map<String, Object> one = new LinkedHashMap<>();
                    // VectorHit 给的就是业务主键 —— 不像文本侧要拿 docID 反查 pkMap
                    one.put("pk", hit.uniqueId());
                    one.put("score", hit.score());
                    // 原文另存的那份里按 pk 取；索引没存字段时是空 map
                    one.put("fields", FieldsJson.render(snapshot.searcher().docByPk(hit.uniqueId()), wanted));
                    hitJson.add(one);
                }

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("totalHits", hits.length);
                out.put("totalExact", true);
                // 如实报出这份索引存了哪些字段 —— 调用方据此分清"字段为空"和"索引没存"
                out.put("storedFields", snapshot.searcher().storedFieldNames());
                out.put("hits", hitJson);
                return out;
            });
            return ResponseEntity.ok(body);
        } catch (NoIndexException e) {
            // 没索引、**模型指纹对不上**、索引打不开 —— 都走这里。
            // 它们该做的事是同一件（协调器换个副本重试），所以同一个码；看 message 分是哪一种。
            log.info("收到向量检索请求，但本节点服务不了，回 503：{}", e.getMessage());
            return ResponseEntity.status(503).body(Map.of(
                    "status", "no-index",
                    "message", e.getMessage()));
        } catch (Exception e) {
            log.error("向量检索失败", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", String.valueOf(e.getMessage())));
        }
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

    private static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}
