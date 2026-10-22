package hawk.vector.search;

import ai.onnxruntime.OrtException;
import common.IndexFormatConfig;
import directory.PkMapStore;
import document.Document;
import hawk.vector.graph.JVectorGraph;
import hawk.vector.meta.VectorIndexMeta;
import hawk.vector.model.ModelDescriptor;
import hawk.vector.model.TextEncoder;
import hawk.vector.query.VectorCondition;
import hawk.vector.stored.StoredFieldsReader;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 向量检索 —— 对标 {@code recall.search.Searcher}。
 *
 * <pre>
 *   try (var s = VectorSearcher.open(Path.of("index-data/goods-vec"))) {
 *       for (VectorHit h : s.search("老黄冰糖", 10)) {
 *           System.out.println(h.uniqueId() + "\t" + h.score());
 *       }
 *   }
 * </pre>
 *
 * <h2>多字段怎么查</h2>
 * 每个字段一张图，各查一遍，再按权重把分数加起来。<b>query 只编码一次</b> ——
 * 所有字段共用一个模型，同一个查询向量可以直接喂给每张图。
 *
 * <p><b>可以只查其中几个字段</b>（{@link #search(float[], int, int, Set)}）—— 正因为图是逐字段
 * 独立存的，"跳过某张图"不需要任何额外数据。这在"多字段混一张图"的设计里做不到。
 *
 * <p>更复杂的查询用 {@link VectorCondition} 的树：{@code Text}（可选字段）+ {@code Group}
 * （AND/OR），见 {@link #search(VectorCondition, int)}。
 *
 * <p><b>融合用加权线性，不用 RRF。</b> RRF（倒数排名融合）存在的意义是规避不可比的分数
 * —— 比如 BM25 分数和余弦相似度量纲完全不同。而这里所有字段出自同一个模型，余弦分数
 * 天然同量纲，加权线性既简单又不丢信息。
 *
 * <p>实测（10 万 × 512，本机）：单图检索 1.75 ms，两图 3.9 ms，**融合本身的开销在噪声内**。
 * 所以这里就用最直白的写法，不做任何为了"快"的复杂化。
 */
public class VectorSearcher implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(VectorSearcher.class);

    /**
     * 每个字段取回的候选数 = {@code max(topK × CANDIDATE_FACTOR, CANDIDATE_MIN)}。
     *
     * <p>不能只取 topK：某个文档在 A 字段排第 11、在 B 字段排第 1，若每张图只取 10 条，
     * 它在 A 里的贡献就丢了。多取一些给融合留出空间 —— 代价很小，因为 jvector 的开销
     * 主要由 rerankK（候选池）决定，topK 只影响结果堆的大小。
     */
    private static final int CANDIDATE_FACTOR = 4;
    private static final int CANDIDATE_MIN = 64;

    /** 一个字段的索引：图 + 该图 ordinal → 主键 的对照表 + 融合权重。 */
    private static final class FieldIndex {
        final String name;
        final float weight;
        final JVectorGraph graph;
        /**
         * 该图里 ordinal → 主键。**每张图各自一份**：缺这个字段的文档不会进这张图，
         * 所以各图的 ordinal 空间并不一致，不能共用。
         */
        final long[] ordinalToKey;

        FieldIndex(String name, float weight, JVectorGraph graph, long[] ordinalToKey) {
            this.name = name;
            this.weight = weight;
            this.graph = graph;
            this.ordinalToKey = ordinalToKey;
        }

        long keyOf(int ordinal) {
            return (ordinal >= 0 && ordinal < ordinalToKey.length) ? ordinalToKey[ordinal] : -1L;
        }

        void close() {
            graph.close();
        }
    }

    private final Path indexDir;
    private final VectorIndexMeta meta;
    private final List<FieldIndex> fieldIndexes;
    private final TextEncoder encoder;
    /** 存储字段（文档原文）。没有配 storedFields 的索引也**有**它 —— 只是里面没有字段。 */
    private final StoredFieldsReader storedFields;
    /** 检索时的候选池大小（HNSW 的 efSearch）。 */
    private int rerankK = JVectorGraph.DEFAULT_RERANK_K;

    private VectorSearcher(Path indexDir, VectorIndexMeta meta,
                           List<FieldIndex> fieldIndexes, TextEncoder encoder,
                           StoredFieldsReader storedFields) {
        this.indexDir = indexDir;
        this.meta = meta;
        this.fieldIndexes = fieldIndexes;
        this.encoder = encoder;
        this.storedFields = storedFields;
    }

    /** 打开索引；向量载入堆（检索快，但每张图占约 N × 维度 × 4 字节）。 */
    public static VectorSearcher open(Path indexDir) throws IOException, OrtException {
        return open(indexDir, null, true);
    }

    public static VectorSearcher open(Path indexDir, boolean cacheVectors)
            throws IOException, OrtException {
        return open(indexDir, null, cacheVectors);
    }

    /**
     * 打开索引。
     *
     * @param modelDir 模型目录；null 表示用 {@code meta.json} 里记的那个（同一台机器上通常够用）。
     *                 换机部署时应当显式传。
     * @param cacheVectors 见 {@link JVectorGraph#load(Path, boolean)}。<b>字段一多就该关掉</b>：
     *                     3 个字段载入堆是 600 MB 量级，磁盘驻留每张图只要约 5 MB。
     */
    public static VectorSearcher open(Path indexDir, Path modelDir, boolean cacheVectors)
            throws IOException, OrtException {
        VectorIndexMeta meta = VectorIndexMeta.load(indexDir);
        Path resolvedModel = modelDir != null ? modelDir
                : (meta.modelDir == null ? null : Path.of(meta.modelDir));
        if (resolvedModel == null) {
            throw new IOException("meta.json 里没记模型目录，也没有显式传入 modelDir");
        }

        TextEncoder encoder = new TextEncoder(resolvedModel);
        try {
            verifyModelMatches(meta, encoder);
        } catch (IOException e) {
            encoder.close();
            throw e;
        }

        // 权重归一化，让"只给一个字段配权重"也能按直觉工作（0.7 : 1.0，而不是 0.7 : 0.0）
        float sum = 0;
        for (VectorIndexMeta.FieldMeta fm : meta.fields) {
            sum += fm.weight;
        }
        if (sum <= 0) {
            sum = Math.max(1, meta.fields.size());
        }

        // ★ 磁盘格式版本：旧格式（没有存储字段这一层）**直接拒绝**，要求重建。
        //   与文本索引对 BKD 格式的做法一致（MMapDirectoryReader.loadBkd 要求 >= CURRENT）——
        //   宁可要求重建，也不留"半吊子状态"：否则同一个查询打到不同版本的分片上，
        //   一个有 fields 一个没有，而两边看起来都正常。
        if (meta.formatVersion < VectorIndexMeta.FORMAT_VERSION) {
            encoder.close();
            throw new IOException("向量索引格式过旧（meta.formatVersion=" + meta.formatVersion
                    + "，当前要求 " + VectorIndexMeta.FORMAT_VERSION + "）："
                    + indexDir.toAbsolutePath() + "\n  需要重建索引");
        }
        StoredFieldsReader storedReader;
        try {
            storedReader = StoredFieldsReader.open(indexDir, new IndexFormatConfig());
        } catch (IOException | RuntimeException e) {
            encoder.close();
            throw e;
        }

        List<FieldIndex> indexes = new ArrayList<>();
        try {
            for (VectorIndexMeta.FieldMeta fm : meta.fields) {
                Path fieldDir = indexDir.resolve(fm.name);
                Path graphFile = fieldDir.resolve(VectorIndexMeta.GRAPH_FILE);
                if (!graphFile.toFile().exists()) {
                    log.warn("字段 '{}' 没有图文件（{}），跳过该字段", fm.name, graphFile);
                    continue;
                }
                JVectorGraph graph = JVectorGraph.load(graphFile, cacheVectors);
                long[] ordinalToKey = loadKeys(fieldDir, graph.size());
                indexes.add(new FieldIndex(fm.name, fm.weight / sum, graph, ordinalToKey));
            }
        } catch (IOException | RuntimeException e) {
            indexes.forEach(FieldIndex::close);   // 半开状态不留给调用方
            storedReader.close();
            encoder.close();
            throw e;
        }
        if (indexes.isEmpty()) {
            storedReader.close();
            encoder.close();
            throw new IOException("索引里一个可用的字段都没有：" + indexDir.toAbsolutePath());
        }
        log.info("向量索引已加载：{} 个字段 {}，维度 {}，模型 {}",
                indexes.size(), indexes.stream().map(i -> i.name).toList(),
                meta.dim, meta.modelName);
        return new VectorSearcher(indexDir, meta, indexes, encoder, storedReader);
    }

    /**
     * 按业务主键取原文（存储字段）。
     *
     * <p>就是向量检索的"取详情"这一步：{@link VectorHit} 给的是 pk，想要字段值再调这个。
     *
     * @return 该文档的字段；pk 不在这份索引里 → {@code null}；
     *         在、但这份索引没存它的字段 → **空 Document**（两者得分得清）
     */
    public Document docByPk(long pk) {
        return storedFields.docOf(pk);
    }

    /** 这份索引存了哪些字段（空 = 不带原文）。 */
    public java.util.Set<String> storedFieldNames() {
        return storedFields.storedFieldNames();
    }

    // ------------------------------------------------------------------ 检索

    /** 文本查询。只在 REST 层用得到 —— 内部批量检索请复用 {@link #search(float[], int)}。 */
    public VectorHit[] search(String query, int topK) throws OrtException {
        return search(encoder.encodeOne(query, ModelDescriptor.Role.QUERY), topK);
    }

    /** 用已经算好的 query 向量检索（所有字段共用同一个查询向量）。 */
    public VectorHit[] search(float[] queryVector, int topK) {
        return search(queryVector, topK, rerankK);
    }

    public VectorHit[] search(float[] queryVector, int topK, int rerankK) {
        return search(queryVector, topK, rerankK, null);
    }

    /**
     * 在**指定字段**的图上检索。
     *
     * <p>能这么做的原因是索引结构：一个字段一张图、各自一份 {@code pk.map}，所以"跳过某张图"
     * 不需要任何额外数据 —— 这在"多字段混一张图"的设计里是做不到的。
     *
     * @param fields 要搜的字段；{@code null} 或空 = 所有字段。
     *               非空时每个名字都必须在索引里有图，否则抛 {@link IllegalArgumentException}
     *               —— 一个拼错的字段名静默变成"搜全部"，是最难查的那类错
     */
    public VectorHit[] search(float[] queryVector, int topK, int rerankK, Set<String> fields) {
        int candidateK = Math.max(topK * CANDIDATE_FACTOR, CANDIDATE_MIN);
        return topN(searchGraphs(queryVector, candidateK, fields), topK);
    }

    /**
     * 按**查询树**检索（{@link VectorCondition}）。
     *
     * <p>过采样在这里统一算：整棵树的每个 {@code Text} 叶子都取
     * {@code max(topK × 4, 64)} 条候选，这样 {@code AND} 求交时不会因为"每个子句只取 topK 条"
     * 而大面积漏掉。注意这只是降低漏掉的概率、**不能消除** —— 见 {@link VectorCondition.Group}。
     */
    public VectorHit[] search(VectorCondition condition, int topK) throws OrtException {
        int candidateK = Math.max(topK * CANDIDATE_FACTOR, CANDIDATE_MIN);
        return topN(evaluate(condition, candidateK), topK);
    }

    /** 候选池大小。越大召回越高、越慢。见 {@link JVectorGraph#DEFAULT_RERANK_K}。 */
    public VectorSearcher setRerankK(int rerankK) {
        this.rerankK = rerankK;
        return this;
    }

    public VectorIndexMeta meta() {
        return meta;
    }

    /** 索引目录。 */
    public Path indexDir() {
        return indexDir;
    }

    public List<String> fields() {
        return fieldIndexes.stream().map(f -> f.name).toList();
    }

    public int dimension() {
        return meta.dim;
    }

    // ------------------------------------------------------------------ 内部

    /** 递归求值。{@code limit} 是**每个叶子**最多取多少条（已经过采样过，内部不再放大）。 */
    private Map<Long, Float> evaluate(VectorCondition condition, int limit) throws OrtException {
        return switch (condition) {
            case VectorCondition.Text t -> {
                float[] qv = encoder.encodeOne(t.text(), ModelDescriptor.Role.QUERY);
                yield searchGraphs(qv, limit, t.allFields() ? null : new LinkedHashSet<>(t.fields()));
            }
            case VectorCondition.Group g -> evaluateGroup(g, limit);
        };
    }

    private Map<Long, Float> evaluateGroup(VectorCondition.Group group, int limit) throws OrtException {
        List<Map<Long, Float>> parts = new ArrayList<>(group.children().size());
        for (VectorCondition child : group.children()) {
            parts.add(evaluate(child, limit));
        }
        return combine(group.relation(), parts);
    }

    /**
     * AND = 交集（文档必须出现在**每一个** part 里），OR = 并集。
     * 两种关系的分数都是**命中子句的分数之和** —— 所有子句过同一个模型，余弦同量纲，直接相加即可。
     *
     * <p>抽成静态是为了能脱离索引与模型单测：这是树求值里唯一有分支逻辑的部分。
     *
     * @param parts 每个子句的结果（pk → 分数）；空列表 → 空结果
     */
    static Map<Long, Float> combine(VectorCondition.Relation relation, List<Map<Long, Float>> parts) {
        if (parts == null || parts.isEmpty()) {
            return Map.of();
        }
        if (relation == VectorCondition.Relation.OR) {
            Map<Long, Float> union = new LinkedHashMap<>();
            for (Map<Long, Float> part : parts) {
                part.forEach((pk, score) -> union.merge(pk, score, Float::sum));
            }
            return union;
        }
        // 交集：从第一份出发，逐个与后面的取交；分数累加 ——
        // 越是被所有子句都排在前面的文档，留下来越靠前
        Map<Long, Float> intersection = new LinkedHashMap<>(parts.get(0));
        for (int i = 1; i < parts.size(); i++) {
            Map<Long, Float> next = new LinkedHashMap<>();
            for (Map.Entry<Long, Float> entry : intersection.entrySet()) {
                Float score = parts.get(i).get(entry.getKey());
                if (score != null) {
                    next.put(entry.getKey(), entry.getValue() + score);
                }
            }
            intersection = next;
        }
        return intersection;
    }

    /** 在（可选的）字段子集上检索，返回最多 {@code candidateK} 条。**内部不再放大候选数。** */
    private Map<Long, Float> searchGraphs(float[] queryVector, int candidateK, Set<String> fields) {
        List<FieldIndex> targets = (fields == null || fields.isEmpty())
                ? fieldIndexes
                : selectFields(fields);
        VectorFloat<?> qv = JVectorGraph.toVector(queryVector);

        // 主键 → 融合分。用 LinkedHashMap 而不是 TreeMap：只在最后排一次序
        Map<Long, Float> merged = new LinkedHashMap<>();
        for (FieldIndex fi : targets) {
            JVectorGraph.Hits hits = fi.graph.search(qv, candidateK, rerankK);
            int[] ordinals = hits.ordinals();
            float[] scores = hits.scores();
            for (int i = 0; i < ordinals.length; i++) {
                long key = fi.keyOf(ordinals[i]);
                if (key >= 0) {
                    merged.merge(key, fi.weight * scores[i], Float::sum);
                }
            }
        }
        return merged;
    }

    /**
     * 字段名 → 实际存在的图。
     *
     * @throws IllegalArgumentException 有名字在索引里不存在 —— **不静默忽略**：
     *         那样一个拼错的字段名会变成"少搜了一个字段"，结果看起来正常但就是不对
     */
    private List<FieldIndex> selectFields(Set<String> fields) {
        List<FieldIndex> selected = new ArrayList<>(fields.size());
        for (String name : fields) {
            FieldIndex match = null;
            for (FieldIndex fi : fieldIndexes) {
                if (fi.name.equals(name)) {
                    match = fi;
                    break;
                }
            }
            if (match == null) {
                throw new IllegalArgumentException("索引里没有向量字段「" + name + "」。现有字段："
                        + fieldIndexes.stream().map(f -> f.name).toList());
            }
            selected.add(match);
        }
        return selected;
    }

    private static VectorHit[] topN(Map<Long, Float> merged, int topK) {
        return merged.entrySet().stream()
                .sorted((a, b) -> Float.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(e -> new VectorHit(e.getKey(), e.getValue()))
                .toArray(VectorHit[]::new);
    }

    /**
     * 加载 ordinal → 主键 的对照表。
     *
     * <p>{@code pk.map} 存的方向是 主键 → ordinal（复用 {@code directory.PkMapStore}），
     * 这里翻成数组：检索拿到的是 ordinal，要的是主键。
     */
    private static long[] loadKeys(Path fieldDir, int size) throws IOException {
        Map<Long, Integer> pkMap = PkMapStore.load(fieldDir);
        long[] ordinalToKey = new long[size];
        Arrays.fill(ordinalToKey, -1L);
        for (Map.Entry<Long, Integer> e : pkMap.entrySet()) {
            int ordinal = e.getValue() == null ? -1 : e.getValue();
            if (ordinal >= 0 && ordinal < size) {
                ordinalToKey[ordinal] = e.getKey();
            }
        }
        if (pkMap.size() != size) {
            log.warn("{} 的 pk.map 有 {},d 条，图里有 {},d 个节点 —— 对不上的文档会取不到主键",
                    fieldDir, pkMap.size(), size);
        }
        return ordinalToKey;
    }

    /**
     * 校验索引与模型是否匹配 —— README 一直声称"由调用方校验"，这里终于把它落地。
     *
     * <p>维度对不上必然出垃圾结果；指纹对不上则是"换了模型但没重建索引"。
     * 两者都在**打开时**就失败，而不是等到检索结果变成噪声才被发现。
     */
    private static void verifyModelMatches(VectorIndexMeta meta, TextEncoder encoder)
            throws IOException {
        if (meta.dim > 0 && meta.dim != encoder.dim()) {
            throw new IOException("索引维度 " + meta.dim + " 与模型维度 " + encoder.dim()
                    + " 不一致（索引由 " + meta.modelName + " 建的，当前模型是 "
                    + encoder.descriptor().name + "）。换模型必须重建索引。");
        }
        String fingerprint = encoder.descriptor().fingerprint;
        if (meta.modelFingerprint != null && fingerprint != null
                && !meta.modelFingerprint.equals(fingerprint)) {
            throw new IOException("索引记的模型指纹 " + meta.modelFingerprint
                    + " 与当前模型 " + fingerprint + " 不一致。换模型必须重建索引。");
        }
    }

    @Override
    public void close() {
        for (FieldIndex fi : fieldIndexes) {
            fi.close();
        }
        storedFields.close();
        encoder.close();
    }
}
