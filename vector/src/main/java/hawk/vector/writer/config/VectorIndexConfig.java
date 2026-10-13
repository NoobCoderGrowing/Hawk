package hawk.vector.writer.config;

import hawk.vector.graph.JVectorGraph;
import hawk.vector.model.OnnxDevice;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 建索引配置 —— 对标 {@code indexer.writer.config.IndexConfig}。
 *
 * <p>与 {@code IndexConfig} 的一个关键差别：<b>字段列表在这里，不在文档里</b>。
 * 倒排那边"索引哪些字段"只是"把哪些字段抄进索引"，文档里有什么就写什么；
 * 而向量这边字段集**决定了索引的物理结构**（一个字段就是一张独立的 HNSW 图），
 * 所以必须由配置显式声明。
 *
 * <p>用法：
 * <pre>
 *   var config = new VectorIndexConfig(List.of("title", "descript"))
 *           .setWeights(Map.of("title", 0.7f, "descript", 0.3f));
 * </pre>
 */
public class VectorIndexConfig {

    /** 默认模型目录：bge-small-zh-v1.5。 */
    public static final Path DEFAULT_MODEL_DIR = Path.of("model/bge-small-zh-v1.5");

    /** 要向量化的字段名。**同时是白名单** —— 没列的字段不进索引。 */
    private final List<String> vectorFields;

    private Path modelDir = DEFAULT_MODEL_DIR;

    /** 字段权重，供检索侧融合用。缺省的字段按 1.0，最终会归一化。 */
    private Map<String, Float> weights = Map.of();

    /** 文档里主键字段的名字。与 {@code IndexConfig#primaryKeyFieldName} 同义同默认。 */
    private String primaryKeyFieldName = "uniqueID";

    private int m = JVectorGraph.DEFAULT_M;

    private int efConstruction = JVectorGraph.DEFAULT_EF_CONSTRUCTION;

    /** 编码设备。默认 {@link OnnxDevice.Kind#AUTO}：有 GPU 用 GPU，没有回退 CPU。 */
    private OnnxDevice.Kind device = OnnxDevice.Kind.AUTO;

    /** CUDA 设备号。 */
    private int gpuDevice = 0;

    /**
     * 可选：CUDA 运行时库所在目录。非 null 时 {@link OnnxDevice#preloadNativeLibs} 会先把它
     * {@code System.load} 进来，免设 {@code LD_LIBRARY_PATH}（见 gpu profile 的注释）。
     */
    private Path cudaLibDir;

    /**
     * @param vectorFields 要向量化的字段名，至少一个
     */
    public VectorIndexConfig(List<String> vectorFields) {
        if (vectorFields == null || vectorFields.isEmpty()) {
            throw new IllegalArgumentException("至少要指定一个要向量化的字段");
        }
        this.vectorFields = List.copyOf(vectorFields);
    }

    /** 主键字段名默认 {@code uniqueID} 的便捷构造 —— 与 {@code IndexConfig} 同一个默认值。 */
    public static VectorIndexConfig of(String... vectorFields) {
        return new VectorIndexConfig(List.of(vectorFields));
    }

    public List<String> getVectorFields() {
        return vectorFields;
    }

    public Path getModelDir() {
        return modelDir;
    }

    public VectorIndexConfig setModelDir(Path modelDir) {
        this.modelDir = modelDir;
        return this;
    }

    /**
     * 该字段的融合权重。**没配的字段按 1.0**，且所有字段的权重会被归一化，
     * 所以只给一个字段配 0.7 而另一个不配，等同于 0.7 : 1.0，不是 0.7 : 0.0。
     */
    public float weightOf(String field) {
        Float w = weights.get(field);
        return w == null ? 1.0f : w;
    }

    public Map<String, Float> getWeights() {
        return weights;
    }

    public VectorIndexConfig setWeights(Map<String, Float> weights) {
        this.weights = new LinkedHashMap<>(weights);
        return this;
    }

    public String getPrimaryKeyFieldName() {
        return primaryKeyFieldName;
    }

    public VectorIndexConfig setPrimaryKeyFieldName(String primaryKeyFieldName) {
        this.primaryKeyFieldName = primaryKeyFieldName;
        return this;
    }

    public int getM() {
        return m;
    }

    public VectorIndexConfig setM(int m) {
        this.m = m;
        return this;
    }

    public int getEfConstruction() {
        return efConstruction;
    }

    public VectorIndexConfig setEfConstruction(int efConstruction) {
        this.efConstruction = efConstruction;
        return this;
    }

    public OnnxDevice.Kind getDevice() {
        return device;
    }

    public VectorIndexConfig setDevice(OnnxDevice.Kind device) {
        this.device = device;
        return this;
    }

    public int getGpuDevice() {
        return gpuDevice;
    }

    public VectorIndexConfig setGpuDevice(int gpuDevice) {
        this.gpuDevice = gpuDevice;
        return this;
    }

    public Path getCudaLibDir() {
        return cudaLibDir;
    }

    public VectorIndexConfig setCudaLibDir(Path cudaLibDir) {
        this.cudaLibDir = cudaLibDir;
        return this;
    }
}
