package hawk.web;

import directory.MMapDirectory;
import document.Document;
import field.PrimaryKeyField;
import field.StringField;
import common.IndexFormatConfig;
import hawk.recall.config.SearchConfig;
import hawk.recall.query.StringQuery;
import hawk.recall.reader.DirectoryReader;
import hawk.recall.search.ScoreDoc;
import hawk.recall.search.Searcher;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 检索演示台的后端：倒排一路（{@link Searcher} + {@link StringQuery}，BM25 打分）。
 *
 * <p>启动时一次性加载索引，之后所有请求只读。
 *
 * <p><b>向量那一路已移除。</b> vector 模块正在重建（见 {@code VECTOR_MODULE_REBUILD.md}），
 * 它的对外形状（{@code VectorIndexWriter} / {@code VectorSearcher}）与旧实现完全不同，
 * 所以这里先只保留倒排。等向量侧重写完再按新 API 接回来 —— 那时应当复用
 * {@code VectorSearcher}，而不是像以前那样在这里自己读 {@code .npy}、自己建 HNSW。
 */
@Service
public class SearchService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    /** 用于查找商品的字段。 */
    private static final String FIELD = "title";

    @Value("${hawk.web.index-dir}")
    private String indexDir;

    private NShortestPathAnalyzer analyzer;
    private DirectoryReader reader;
    private Searcher searcher;

    @PostConstruct
    void load() throws Exception {
        Path idx = Paths.get(indexDir);
        if (!Files.exists(idx.resolve("segment.info"))) {
            throw new IllegalStateException(
                    "索引目录里没有 segment.info：" + idx.toAbsolutePath()
                            + "\n  先用 goods.csv 建一个索引，或改配置里的 hawk.web.index-dir");
        }

        this.analyzer = new NShortestPathAnalyzer(1);
        // 读路径只需要格式配置（blocSize / LZ4 解压器），用不着 indexer 侧那个 IndexConfig，
        // 所以 web 模块不必依赖 indexer。
        this.reader = DirectoryReader.open(MMapDirectory.open(idx));
        this.searcher = new Searcher(reader, new SearchConfig(analyzer), new IndexFormatConfig());
        log.info("倒排索引已加载: {} ({} 篇)", idx.toAbsolutePath(), reader.getTotalDoc());
    }

    // ------------------------------------------------------------------ 检索

    /**
     * 词项检索。{@link StringQuery} 会先分词，再按 MUST（全部词命中）查，
     * 没有结果时退化成 SHOULD（任一命中）—— 与用户"先精确后宽松"的直觉一致。
     */
    public SearchResponse searchFullText(String query, int topK) {
        long t0 = System.nanoTime();
        ScoreDoc[] hits = searcher.search(new StringQuery(FIELD, query), topK);
        long tookMs = (System.nanoTime() - t0) / 1_000_000;

        List<Result> out = new ArrayList<>();
        if (hits != null) {
            for (int i = 0; i < hits.length; i++) {
                Document doc = searcher.doc(hits[i]);
                if (doc == null) {
                    continue;   // 已被软删除
                }
                String title = stringField(doc, FIELD);
                out.add(new Result(i + 1, title, hits[i].getScore(),
                        primaryKey(doc), hits[i].docID));
            }
        }
        String note = (hits == null || hits.length == 0)
                ? "没有命中。全文搜索按词匹配，试试标题里确实会出现的词。"
                : null;
        return new SearchResponse(query, out, tookMs, note, analyzedTerms(query));
    }

    /** 分词结果。前端拿它把标题里命中的词标出来 —— BM25 的分数怎么来的，全靠这一步可解释。 */
    private List<String> analyzedTerms(String query) {
        List<String> terms = new ArrayList<>();
        for (hawk.segment.core.Term t : analyzer.anlyze(query, FIELD)) {
            terms.add(t.getValue());
        }
        return terms;
    }

    public long totalDocs() {
        return reader.getTotalDoc();
    }

    // ------------------------------------------------------------------ 取字段

    private static String stringField(Document doc, String name) {
        Object f = doc.getFieldMap().get(name);
        return f instanceof StringField sf ? sf.getValue() : null;
    }

    /** uniqueID 是 stored 字段，所以取原文时顺带就能拿到，不必反查 pk.map。 */
    private static Long primaryKey(Document doc) {
        Object f = doc.getFieldMap().get("uniqueID");
        return f instanceof PrimaryKeyField pk ? pk.getValue() : null;
    }

    /**
     * 一条命中。{@code docId} 是"索引内部的位置"，不是业务主键；对外统一用 {@code productId}。
     *
     * <p>{@code title} 可能为 null：商品在索引里但已被软删除时 {@code docByUniqueId} 返回 null。
     *
     * <p><b>为什么是普通类而不是 record</b>：HTTP 层的 JSON 用 fastjson 序列化，而 fastjson 1.2.x
     * **不支持 record** —— 会把 record 静默序列化成 {@code {}}（不报错），前端拿到空对象。
     * 所以这里显式给 getter，让 fastjson 按 bean 属性输出。
     */
    public static class Result {
        private final int rank;
        private final String title;
        private final double score;
        private final Long productId;
        private final Integer docId;

        public Result(int rank, String title, double score, Long productId, Integer docId) {
            this.rank = rank;
            this.title = title;
            this.score = score;
            this.productId = productId;
            this.docId = docId;
        }

        public int getRank() {
            return rank;
        }

        public String getTitle() {
            return title;
        }

        public double getScore() {
            return score;
        }

        public Long getProductId() {
            return productId;
        }

        public Integer getDocId() {
            return docId;
        }
    }

    /**
     * 一次检索的完整结果。同样是普通类而非 record，理由见 {@link Result}。
     *
     * <p>{@code note} 用于在界面上解释"为什么是这样"，无话可说时为 null；
     * {@code terms} 是分词结果，供前端在标题里标出命中词。
     * 两者为 null 时 fastjson 默认不输出该键。
     */
    public static class SearchResponse {
        private final String query;
        private final List<Result> hits;
        private final long tookMs;
        private final String note;
        private final List<String> terms;

        public SearchResponse(String query, List<Result> hits, long tookMs,
                              String note, List<String> terms) {
            this.query = query;
            this.hits = hits;
            this.tookMs = tookMs;
            this.note = note;
            this.terms = terms;
        }

        public String getQuery() {
            return query;
        }

        public List<Result> getHits() {
            return hits;
        }

        public long getTookMs() {
            return tookMs;
        }

        public String getNote() {
            return note;
        }

        public List<String> getTerms() {
            return terms;
        }
    }

    @Override
    public void destroy() {
        if (reader != null) {
            reader.close();
        }
    }
}
