package hawk.vector.writer;

import ai.onnxruntime.OrtException;
import directory.PkMapStore;
import document.Document;
import field.Field;
import field.PrimaryKeyField;
import field.StringField;
import hawk.vector.graph.JVectorGraph;
import hawk.vector.meta.VectorIndexMeta;
import hawk.vector.model.ModelDescriptor;
import hawk.vector.model.TextEncoder;
import hawk.vector.writer.config.VectorIndexConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 建向量索引 —— 对标 {@code indexer.writer.IndexWriter}。
 *
 * <pre>
 *   try (var w = new VectorIndexWriter(config, Path.of("index-data/goods-vec"))) {
 *       for (Document d : docs) {
 *           w.addDoc(d);          // 攒批编码
 *       }
 *       w.commit();               // 按字段建图 + 落盘
 *   }
 * </pre>
 *
 * <h2>一个字段 = 一张独立的 HNSW 图</h2>
 * 不能把多个字段的向量塞进同一张图：HNSW 用余弦距离做近邻判断，不同字段的向量混在
 * 一起会互相做邻居、污染召回。这是结构性的，调参救不了 —— 也是 Qdrant 要 named vectors、
 * Elasticsearch 要多个 {@code dense_vector} 字段的原因。
 *
 * <p>字段集由 {@link VectorIndexConfig#getVectorFields()} 声明，**同时是白名单**：
 * 没列的字段不进索引。
 *
 * <h2>输入</h2>
 * 两条入口：
 * <ul>
 *   <li>{@link #addDoc(Document)} —— 主路径：从 {@code Document} 取字段文本，用 ONNX 编码</li>
 *   <li>{@link #addVector(String, long, float[])} —— 喂**预计算好的**向量，不加载模型。
 *       给"向量已由别处算好"（如 Python 侧）以及无需模型的测试用</li>
 * </ul>
 *
 * <h2>内存</h2>
 * 编码结果要全部留在堆里，直到 {@code commit()} 才建图（jvector 的
 * {@code GraphIndexBuilder} 是批量接口）。所以峰值约为
 * <b>字段数 × 文档数 × 维度 × 4 字节</b> —— 10 万 × 512 × 3 字段约 615 MB。
 * 目前只做一次性批量；要支持增量（边收边建图）才能把峰值降到与文档数无关。
 */
public class VectorIndexWriter implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexWriter.class);

    /**
     * 攒够多少条文本就编码一次。
     *
     * <p>不用逐条编码：{@code TextEncoder.encode} 会按 {@code model.json} 的
     * {@code encodeBatchSize} 再分块，喂大一点只是减少调用次数。这个值只影响
     * "多久提交一次编码"，不影响结果。
     */
    private static final int FLUSH_THRESHOLD = 512;

    private final VectorIndexConfig config;
    private final Path indexDir;
    private final List<String> fields;

    /** 待编码的文本与它们的主键，逐字段攒批。 */
    private final Map<String, List<String>> pendingTexts = new LinkedHashMap<>();
    private final Map<String, List<Long>> pendingKeys = new LinkedHashMap<>();

    /** 已编码的向量与对应主键，逐字段累积。顺序即该图的 ordinal 顺序。 */
    private final Map<String, List<float[]>> vectors = new LinkedHashMap<>();
    private final Map<String, List<Long>> keys = new LinkedHashMap<>();

    /** 惰性创建：只用 {@link #addVector} 的调用方不必为加载模型付出代价。 */
    private TextEncoder encoder;

    private int dim;
    private long accepted;
    private long droppedNoKey;
    private long droppedNoField;
    private boolean committed;

    public VectorIndexWriter(VectorIndexConfig config, Path indexDir) {
        this.config = config;
        this.indexDir = indexDir;
        this.fields = config.getVectorFields();
        for (String f : fields) {
            pendingTexts.put(f, new ArrayList<>());
            pendingKeys.put(f, new ArrayList<>());
            vectors.put(f, new ArrayList<>());
            keys.put(f, new ArrayList<>());
        }
    }

    // ------------------------------------------------------------------ 收文档

    /**
     * 收一个文档：按配置的字段集取出文本，攒够一批就编码。
     *
     * <p>行为约定（与 {@code IndexService} 处理原始文档时一致：缺主键就整条跳过并计数，
     * 缺某个字段就只是这个字段不收）：
     * <ul>
     *   <li>没有主键 → **整条跳过**（没有主键就无法在检索结果里指认它）</li>
     *   <li>配置的字段一个都没有（或值为空）→ 整条跳过，计入 {@link #droppedNoField()}</li>
     *   <li>只有部分字段 → 收下有的那几个；缺的那个字段的图里**没有这条文档**</li>
     * </ul>
     * 最后一条正是每张图各自配一份 {@code pk.map} 的原因。
     */
    public void addDoc(Document doc) throws IOException, OrtException {
        checkOpen();
        Long key = primaryKeyOf(doc);
        if (key == null) {
            droppedNoKey++;
            log.debug("文档没有主键字段 {}，跳过", config.getPrimaryKeyFieldName());
            return;
        }
        boolean any = false;
        for (String f : fields) {
            String text = textOf(doc, f);
            if (text == null) {
                continue;
            }
            pendingTexts.get(f).add(text);
            pendingKeys.get(f).add(key);
            any = true;
        }
        if (!any) {
            droppedNoField++;
            return;
        }
        accepted++;
        if (maxPending() >= FLUSH_THRESHOLD) {
            flush();
        }
    }

    /**
     * 直接喂一个算好的向量 —— 不加载模型。
     *
     * <p>给"向量由别处算好"的场景（例如 Python 侧已产出的 {@code .npy}）以及
     * 不需要模型的单测。维度以第一次调用为准，后续不一致会直接抛错。
     */
    public void addVector(String field, long uniqueId, float[] vector) {
        checkOpen();
        if (!pendingTexts.containsKey(field)) {
            throw new IllegalArgumentException(
                    "字段 '" + field + "' 不在配置的字段集里：" + fields);
        }
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("空向量");
        }
        checkDim(vector.length);
        vectors.get(field).add(vector);
        keys.get(field).add(uniqueId);
        accepted++;
    }

    // ------------------------------------------------------------------ 提交

    /**
     * 建图并落盘：每个字段一张 HNSW 图，各配一份 {@code pk.map}，
     * 外加一份描述整个索引的 {@code meta.json}。
     *
     * <p>调用后本对象不可再用（只能 {@link #close()}）。
     */
    public void commit() throws IOException, OrtException {
        checkOpen();
        flush();

        if (accepted == 0) {
            throw new IllegalStateException("没有任何可索引的文档："
                    + "主键缺失 " + droppedNoKey + " 条，字段全空 " + droppedNoField + " 条");
        }
        Files.createDirectories(indexDir);

        VectorIndexMeta meta = buildMeta();
        for (String field : fields) {
            List<float[]> rows = vectors.get(field);
            if (rows.isEmpty()) {
                log.warn("字段 '{}' 一条都没收到，不产出图（检查字段名是否与文档里的对得上）", field);
                meta.fields.add(new VectorIndexMeta.FieldMeta(field, config.weightOf(field), 0));
                continue;
            }

            Path fieldDir = indexDir.resolve(field);
            Files.createDirectories(fieldDir);

            // JVectorGraph.build 要完整矩阵；toArray 只复制引用，不复制向量本体
            float[][] matrix = rows.toArray(new float[rows.size()][]);
            try (JVectorGraph graph = JVectorGraph.build(
                    matrix, config.getM(), config.getEfConstruction())) {
                graph.save(fieldDir.resolve(VectorIndexMeta.GRAPH_FILE));
            }

            // ordinal → 主键 反过来存成 主键 → ordinal，直接复用 directory.PkMapStore 的格式
            List<Long> fieldKeys = keys.get(field);
            Map<Long, Integer> pkMap = new LinkedHashMap<>();
            for (int i = 0; i < fieldKeys.size(); i++) {
                pkMap.put(fieldKeys.get(i), i);
            }
            if (pkMap.size() != fieldKeys.size()) {
                log.warn("字段 '{}' 里有重复主键（{},{} 条），pk.map 会互相覆盖",
                        field, fieldKeys.size(), pkMap.size());
            }
            PkMapStore.save(fieldDir, pkMap);

            log.info("字段 '{}'：{},d 条向量建图完成 -> {}",
                    field, rows.size(), fieldDir.resolve(VectorIndexMeta.FILE_NAME).getParent());
            meta.fields.add(new VectorIndexMeta.FieldMeta(field, config.weightOf(field), rows.size()));

            // 这张图已落盘，向量不再需要 —— 早点放开，让 GC 有机会在下一张图前回收
            rows.clear();
            fieldKeys.clear();
        }

        VectorIndexMeta.save(indexDir, meta);
        committed = true;
        log.info("向量索引完成：{},d 条文档，{} 个字段 -> {}（主键缺失 {}，字段全空 {}）",
                accepted, meta.fields.size(), indexDir.toAbsolutePath(), droppedNoKey, droppedNoField);
    }

    private VectorIndexMeta buildMeta() throws IOException, OrtException {
        VectorIndexMeta meta = new VectorIndexMeta();
        meta.modelDir = config.getModelDir().toString();
        meta.primaryKeyField = config.getPrimaryKeyFieldName();
        meta.dim = dim;
        meta.m = config.getM();
        meta.efConstruction = config.getEfConstruction();
        if (encoder != null) {
            // 只有真加载过模型才知道指纹 —— 用 addVector 喂向量的场景没有可校验的模型
            ModelDescriptor d = encoder.descriptor();
            meta.modelName = d.name;
            meta.modelFingerprint = d.fingerprint;
        }
        return meta;
    }

    // ------------------------------------------------------------------ 内部

    private void flush() throws IOException, OrtException {
        for (String field : fields) {
            List<String> texts = pendingTexts.get(field);
            if (texts.isEmpty()) {
                continue;
            }
            float[][] encoded = encoder().encode(texts, ModelDescriptor.Role.DOC);
            // encode 保持输入顺序，所以第 i 个向量对应的就是第 i 个主键
            List<float[]> out = vectors.get(field);
            for (float[] v : encoded) {
                checkDim(v.length);
                out.add(v);
            }
            keys.get(field).addAll(pendingKeys.get(field));
            texts.clear();
            pendingKeys.get(field).clear();
        }
    }

    private int maxPending() {
        int max = 0;
        for (List<String> texts : pendingTexts.values()) {
            max = Math.max(max, texts.size());
        }
        return max;
    }

    private TextEncoder encoder() throws IOException, OrtException {
        if (encoder == null) {
            encoder = new TextEncoder(config.getModelDir(), config.getDevice(),
                    config.getGpuDevice(), config.getCudaLibDir());
        }
        return encoder;
    }

    private void checkDim(int vectorDim) {
        if (dim == 0) {
            dim = vectorDim;
            return;
        }
        if (dim != vectorDim) {
            throw new IllegalArgumentException(
                    "向量维度不一致：先前是 " + dim + "，这次是 " + vectorDim
                            + "。同一个索引里的所有向量必须同维、且来自同一个模型。");
        }
    }

    private Long primaryKeyOf(Document doc) {
        Field f = doc.getFieldMap().get(config.getPrimaryKeyFieldName());
        return f instanceof PrimaryKeyField pk ? pk.getValue() : null;
    }

    /** 取字段文本。**只有 {@link StringField} 会被向量化** —— 把数字直接编码成语义向量没有意义。 */
    private String textOf(Document doc, String fieldName) {
        Field f = doc.getFieldMap().get(fieldName);
        if (f instanceof StringField sf) {
            String v = sf.getValue();
            return (v == null || v.isBlank()) ? null : v;
        }
        return null;
    }

    private void checkOpen() {
        if (committed) {
            throw new IllegalStateException("已经 commit 过了，本 writer 不可再用");
        }
    }

    // ------------------------------------------------------------------ 统计

    /** 收下的文档条数。 */
    public long accepted() {
        return accepted;
    }

    /** 因为没有主键而整条跳过的文档数。 */
    public long droppedNoKey() {
        return droppedNoKey;
    }

    /** 因为配置的字段一个都没有而整条跳过的文档数。 */
    public long droppedNoField() {
        return droppedNoField;
    }

    public int dimension() {
        return dim;
    }

    @Override
    public void close() {
        if (encoder != null) {
            encoder.close();
        }
    }
}
