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
import hawk.vector.index.JVectorIndex;
import hawk.vector.index.NpyReader;
import hawk.vector.index.SearchHits;
import hawk.vector.model.ModelDescriptor;
import hawk.vector.model.TextEncoder;

import io.github.jbellis.jvector.util.Bits;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 把两条召回链路装进一个进程：
 *
 * <ul>
 *   <li><b>倒排</b>：{@link Searcher} + {@link StringQuery}，BM25 打分</li>
 *   <li><b>向量</b>：{@link TextEncoder} 编码 query → {@link JVectorIndex} 取 top-K</li>
 * </ul>
 *
 * <h2>向量这一侧的完整链路</h2>
 * 向量索引只给出 <b>ordinal</b>（行号）。要拿到商品原文，得走：
 * <pre>
 *   ordinal → ids[ordinal] = 商品ID → Searcher.docByUniqueId(商品ID) → 商品标题
 * </pre>
 * 中间的 {@code pk.map} 查表由 {@code docByUniqueId} 封装 —— 商品 ID 与全局 docID
 * 数值上毫无关系（docID 按索引线程完成顺序分配），混用不会报错、只会静默返回别的商品，
 * 所以这一层不能绕开。
 *
 * <p>启动时一次性加载索引与模型，之后所有请求只读。索引构建（10 万条向量建 HNSW）
 * 需要十几秒，属预期。
 */
@Service
public class SearchService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    /** 用于查找商品的字段。倒排与向量两侧都取这一个字段。 */
    private static final String FIELD = "title";

    @Value("${hawk.web.index-dir}")
    private String indexDir;

    @Value("${hawk.web.vector-dir}")
    private String vectorDir;

    @Value("${hawk.web.model-dir}")
    private String modelDir;

    private NShortestPathAnalyzer analyzer;
    private DirectoryReader reader;
    private Searcher searcher;
    private JVectorIndex vectorIndex;
    private TextEncoder encoder;

    /** ordinal → 商品 ID。向量索引不存业务主键，这层映射是必需的。 */
    private long[] productIds;

    @PostConstruct
    void load() throws Exception {
        Path idx = Paths.get(indexDir);
        if (!Files.exists(idx.resolve("segment.info"))) {
            throw new IllegalStateException(
                    "索引目录里没有 segment.info：" + idx.toAbsolutePath()
                            + "\n  先用 goods.csv 建一个索引，或改 application.yml 里的 hawk.web.index-dir");
        }

        this.analyzer = new NShortestPathAnalyzer(1);
        // 读路径只需要格式配置（blocSize / LZ4 解压器），用不着 indexer 侧那个 IndexConfig，
        // 所以 web 模块不必依赖 indexer。
        this.reader = DirectoryReader.open(MMapDirectory.open(idx));
        this.searcher = new Searcher(reader, new SearchConfig(analyzer), new IndexFormatConfig());
        log.info("倒排索引已加载: {} ({} 篇)", idx.toAbsolutePath(), reader.getTotalDoc());

        Path vec = Paths.get(vectorDir);
        Path embPath = vec.resolve("corpus_emb.npy");
        Path idsPath = vec.resolve("corpus_emb_ids.npy");
        if (!Files.exists(embPath) || !Files.exists(idsPath)) {
            throw new IllegalStateException(
                    "向量文件缺失：需要 " + embPath.toAbsolutePath() + " 与 " + idsPath.toAbsolutePath()
                            + "\n  生成方式见 README §4.3");
        }
        // 注意内存：全部读成 fp32 后是 N×D×4 字节，10 万×512 约 205 MB，
        // 加上 HNSW 图结构还要更多 —— 启动要 -Xmx3g 起步。
        float[][] matrix = NpyReader.readFloatMatrix(embPath);
        this.productIds = NpyReader.readLongArray(idsPath);
        if (matrix.length != productIds.length) {
            throw new IllegalStateException("向量条数 " + matrix.length
                    + " 与 ID 条数 " + productIds.length + " 不一致");
        }
        this.vectorIndex = JVectorIndex.build(matrix);
        log.info("向量索引已建立: {} 条 × {} 维", matrix.length, matrix[0].length);

        this.encoder = new TextEncoder(Paths.get(modelDir));
        log.info("模型已加载: {}", encoder.descriptor().name);
    }

    // ------------------------------------------------------------------ 全文搜索

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
                out.add(new Result(i + 1, title, hits[i].getScore(), primaryKey(doc),
                        hits[i].docID, null));
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

    // ------------------------------------------------------------------ 向量搜索

    /**
     * 语义检索。query 文本 → 向量 → HNSW 取 top-K → ordinal → 商品 ID → 回倒排取原文。
     */
    public SearchResponse searchVector(String query, int topK) throws Exception {
        long t0 = System.nanoTime();
        float[] vec = encoder.encodeOne(query, ModelDescriptor.Role.QUERY);
        long encodeMs = System.nanoTime() - t0;

        SearchHits hits = vectorIndex.search(vec, topK, Bits.ALL);
        int[] ordinals = hits.ordinals();

        List<Result> out = new ArrayList<>(ordinals.length);
        for (int i = 0; i < ordinals.length; i++) {
            int ordinal = ordinals[i];
            long productId = productIds[ordinal];

            // 关键一跳：商品 ID 在倒排这一侧才有原文
            Document doc = searcher.docByUniqueId(productId);
            String title = doc == null ? null : stringField(doc, FIELD);

            out.add(new Result(i + 1, title, hits.scores()[i], productId, null, ordinal));
        }
        long tookMs = (System.nanoTime() - t0) / 1_000_000;
        // 向量这一路没有"命中词"可言 —— 它是整句语义匹配，不是词项匹配。
        return new SearchResponse(query, out, tookMs,
                "其中编码 query 用了 " + encodeMs / 1_000_000 + " ms（ONNX 推理）。", null);
    }

    public long totalDocs() {
        return reader.getTotalDoc();
    }

    public long indexedVectors() {
        return vectorIndex == null ? 0 : vectorIndex.size();
    }

    public String modelName() {
        return encoder == null ? null : encoder.descriptor().name;
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
     * 一条命中。{@code docId} 只在倒排侧有值、{@code ordinal} 只在向量侧有值 ——
     * 两者都是"索引内部的位置"，不是业务主键；对外统一用 {@code productId}。
     *
     * <p>{@code title} 可能为 null：商品在索引里但已被软删除时 {@code docByUniqueId} 返回 null。
     */
    public record Result(int rank, String title, double score, Long productId,
                         Integer docId, Integer ordinal) {
    }

    /**
     * 一次检索的完整结果。
     *
     * @param note  用于在界面上解释"为什么是这样"，无话可说时为 null
     * @param terms 全文搜索的分词结果，供前端在标题里标出命中词；向量搜索为 null
     */
    public record SearchResponse(String query, List<Result> hits, long tookMs,
                                 String note, List<String> terms) {
    }

    @Override
    public void destroy() {
        if (vectorIndex != null) {
            vectorIndex.close();
        }
        if (encoder != null) {
            encoder.close();
        }
        if (reader != null) {
            reader.close();
        }
    }
}
