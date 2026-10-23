package hawk.cloud.coordinator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 把多路（多 role × 多分片）召回的候选**去重 + 融合**成一份有序结果。
 * 方案见 {@code DESIGN.md §5}。
 *
 * <h2>去重：按业务主键</h2>
 * 一篇文档可能被**多条路**召回（比如既被文本 BM25 命中、又被向量近邻命中）。
 * 同一路内部不会重复 —— 文档按 {@code floorMod(pk, shardCount)} 分片，一篇只在一个分片里；
 * **重复只发生在跨路**。所以去重键就是 {@code pk}。
 *
 * <h2>融合：RRF（倒数排名融合），不是分数相加</h2>
 * <pre>
 *   score(doc) = Σ  1 / (RRF_K + rank_of_doc_in_list_i)
 *                每个召回列表 i
 * </pre>
 *
 * <p><b>为什么不能用分数。</b>两个原因，而且第二个人们常忽略：
 * <ol>
 *   <li><b>跨路不可比</b>：BM25 和余弦相似度不是一个量纲，直接比大小或相加都没有意义；</li>
 *   <li><b>跨分片也不可比</b>（文本那一路）：BM25 用的是**本分片**的
 *       {@code totalDoc} / {@code avgDocLength} / {@code df}，所以分片 A 的 8.7 分和
 *       分片 B 的 8.7 分根本不是一回事。</li>
 * </ol>
 * RRF **只看排名**，两个问题一起绕开，而且不需要调参（{@link #RRF_K} 用原论文的 60）。
 *
 * <h2>「召回列表」的粒度是**分片**，不是 route</h2>
 * 这一点很关键：文本那一路的各个分片分数互不可比，**没法把它们拼成一个全局有序列表** ——
 * 硬拼出来的顺序是错的。所以每个分片的返回列表各自算一个排名序列，RRF 在所有序列之间融合。
 * 这样每个序列内部都是"真的有序"的，融合才有意义。
 *
 * <h2>⚠️ 多路时的 totalHits 只能是**上界**</h2>
 * 两条路各自报的命中数相加，会把"两条路都召回到的文档"数两遍。要精确去重就得知道全量，
 * 而这里只有各路的前 N 条。所以多路时 {@code totalExact} **一律置 false** ——
 * 宁可说"这是上界"，也不要把一个虚高的数当精确值给出去。
 */
final class SearchMerger {

    private static final Logger log = LoggerFactory.getLogger(SearchMerger.class);

    /**
     * RRF 的平滑常数。
     *
     * <p>60 来自原论文（Cormack 等, 2009），也是 Elasticsearch 的默认值。
     * 它的作用是压掉"排名第 1 和第 2 之间"的巨大差距，让**在多个列表里都靠前**的文档胜出，
     * 而不是让某个列表的第 1 名一枝独秀。
     */
    static final int RRF_K = 60;

    private SearchMerger() {
    }

    /** 融合后的一条命中。 */
    record Hit(long pk, double score, List<String> routes, Map<String, Object> fields) {
    }

    /** 融合结果。 */
    record Merged(List<Hit> hits, Map<String, Object> routeSummary) {
    }

    /**
     * @param routes 各路的原始结果（一路 = 一个 role 的一轮扇出）
     * @param limit  最终要几条
     */
    static Merged merge(List<ShardSearchClient.RouteResult> routes, int limit) {
        // pk → 累计 RRF 分 + 它出现在哪些路 + 它的字段（各路互补填空）
        Map<Long, Double> scores = new LinkedHashMap<>();
        Map<Long, TreeSet<String>> docRoutes = new LinkedHashMap<>();
        Map<Long, Map<String, Object>> docFields = new LinkedHashMap<>();

        for (ShardSearchClient.RouteResult route : routes) {
            for (ShardSearchClient.ShardHits shard : route.shards()) {
                // ★ 一个分片的列表 = 一个有序序列。rank 从 1 开始
                int rank = 0;
                for (Map<String, Object> raw : shard.hits()) {
                    rank++;
                    Long pk = pkOf(raw);
                    if (pk == null) {
                        continue;                       // 分片没给主键 —— 没法去重，只能丢
                    }
                    scores.merge(pk, 1.0 / (RRF_K + rank), Double::sum);
                    docRoutes.computeIfAbsent(pk, k -> new TreeSet<>()).add(route.recallRole());
                    Map<String, Object> fields = fieldsOf(raw);
                    if (!fields.isEmpty()) {
                        // 各路互补：某条路没存这个字段时，另一条路的值会填进来。
                        // 都存了的话后一路覆盖前一路 —— 正常情况下两边应该一致
                        docFields.computeIfAbsent(pk, k -> new LinkedHashMap<>()).putAll(fields);
                    }
                }
            }
        }

        List<Hit> hits = new ArrayList<>(scores.size());
        for (Map.Entry<Long, Double> e : scores.entrySet()) {
            hits.add(new Hit(e.getKey(), e.getValue(),
                    new ArrayList<>(docRoutes.get(e.getKey())),
                    docFields.getOrDefault(e.getKey(), Map.of())));
        }
        // 分数降序；**同分按 pk 升序** —— 不这么定，两次同样的查询可能给出不同的顺序
        hits.sort(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparingLong(Hit::pk));
        if (hits.size() > limit) {
            hits = new ArrayList<>(hits.subList(0, limit));
        }

        Map<String, Object> summary = summarize(routes);
        log.debug("归并：{} 路 / {} 个分片 -> {} 条候选，取前 {}", routes.size(),
                routes.stream().mapToInt(r -> r.shards().size()).sum(), scores.size(), limit);
        return new Merged(hits, summary);
    }

    /** 每路报一个"命中多少、精确与否、哪些分片没回来" —— 调用方据此判断结果可不可信。 */
    private static Map<String, Object> summarize(List<ShardSearchClient.RouteResult> routes) {
        Map<String, Object> out = new LinkedHashMap<>();
        int totalSum = 0;
        int okShards = 0;
        Map<String, String> allFailed = new LinkedHashMap<>();
        boolean allExact = true;

        for (ShardSearchClient.RouteResult route : routes) {
            Map<String, Object> one = new LinkedHashMap<>();
            int sum = route.shards().stream().mapToInt(ShardSearchClient.ShardHits::totalHits).sum();
            boolean exact = route.shards().stream().allMatch(ShardSearchClient.ShardHits::totalExact);
            one.put("totalHits", sum);
            one.put("totalExact", exact);
            one.put("okShards", route.shards().size());
            one.put("failed", route.failed());
            out.put(route.recallRole(), one);

            totalSum += sum;
            okShards += route.shards().size();
            allExact &= exact;
            route.failed().forEach((shard, why) -> allFailed.put(route.recallRole() + "/" + shard, why));
        }

        Map<String, Object> root = new LinkedHashMap<>();
        // ★ 多路时去重做不到精确 —— 把两条路各自报的数相加会把公共文档数两遍。
        //   所以：单路且各分片都精确，才是精确的
        root.put("totalHits", totalSum);
        root.put("totalExact", allExact && routes.size() == 1);
        root.put("okShards", okShards);
        root.put("failed", allFailed);
        root.put("routes", out);
        return root;
    }

    private static Long pkOf(Map<String, Object> hit) {
        Object pk = hit.get("pk");
        return pk instanceof Number n ? n.longValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldsOf(Map<String, Object> hit) {
        Object fields = hit.get("fields");
        return fields instanceof Map ? (Map<String, Object>) fields : Map.of();
    }
}
