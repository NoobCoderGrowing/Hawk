package hawk.vector.graph;

import io.github.jbellis.jvector.disk.ReaderSupplier;
import io.github.jbellis.jvector.disk.ReaderSupplierFactory;
import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.GraphSearcher;
import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import io.github.jbellis.jvector.graph.ListRandomAccessVectorValues;
import io.github.jbellis.jvector.graph.RandomAccessVectorValues;
import io.github.jbellis.jvector.graph.SearchResult;
import io.github.jbellis.jvector.graph.disk.OnDiskGraphIndex;
import io.github.jbellis.jvector.util.Bits;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 一张 jvector（HNSW）图 —— **本模块的内部实现**，不对模块外暴露。
 *
 * <p>用法见 {@code VectorIndexWriter}（建）与 {@code VectorSearcher}（查）。
 * 之所以不对外：一个字段就是一张图，多字段就是多张图，把"图"暴露出去会让调用方
 * 被迫自己处理 ordinal 对齐与融合 —— 那是 {@code VectorSearcher} 的职责。
 *
 * <p>选 jvector 的理由：纯 Java 无 JNI、磁盘驻留、内建 PQ/BQ 量化、为增量更新设计。
 *
 * <p><b>ordinal</b>：图里的第 i 个节点就是建图时喂入的第 i 个向量。本类不做任何主键映射 ——
 * 那是 {@code VectorIndexWriter} 的职责（它写 {@code PK.map}）。
 */
public final class JVectorGraph implements Closeable {

    /** HNSW 每个节点的邻居数。越大召回越高、内存越多。16 是常用默认。 */
    public static final int DEFAULT_M = 16;

    /** 建索引时的候选队列大小（jvector 里叫 beamWidth）。越大建得越慢、图质量越好。 */
    public static final int DEFAULT_EF_CONSTRUCTION = 100;

    /**
     * 检索时的候选池大小（HNSW 的 efSearch，jvector 的 API 名叫 rerankK）。
     *
     * <p><b>必须显式设置</b>：jvector 的默认值是 {@code rerankK = topK}，候选池小得
     * 刚好只装得下答案，搜索会很快收敛到局部最优就停下。实测 10 万条语料上
     * 默认值召回只有 <b>68%</b>，而 256 能到 <b>97%</b>。
     *
     * <p>实测的召回/延迟权衡（10 万条 × 512 维，500 条 query）：
     * <pre>
     *   ef=10  召回 68.02%   0.30 ms   ← jvector 默认
     *   ef=32  召回 85.64%   0.45 ms
     *   ef=128 召回 94.64%   1.01 ms
     *   ef=256 召回 97.06%   1.74 ms   ← 本默认值
     * </pre>
     * 128→256 花 0.73 ms 只买 2.4 个点，边际收益已经在递减；再往上不划算。
     */
    public static final int DEFAULT_RERANK_K = 256;

    private static final VectorTypeSupport VTS =
            VectorizationProvider.getInstance().getVectorTypeSupport();
    private static final VectorSimilarityFunction SIMILARITY = VectorSimilarityFunction.COSINE;

    /** 检索结果：ordinal 与分数的平行数组，按分数降序。 */
    public record Hits(int[] ordinals, float[] scores) {
        public int size() {
            return ordinals.length;
        }
    }

    private final ImmutableGraphIndex graph;
    private final RandomAccessVectorValues vectors;
    private final int dim;
    private final int size;
    /** 从磁盘加载时持有；内存索引时为 null。**必须由本类关闭** —— 见 close()。 */
    private final ReaderSupplier supplier;

    private JVectorGraph(ImmutableGraphIndex graph, RandomAccessVectorValues vectors,
                         ReaderSupplier supplier, int dim, int size) {
        this.graph = graph;
        this.vectors = vectors;
        this.supplier = supplier;
        this.dim = dim;
        this.size = size;
    }

    // ------------------------------------------------------------------ 建

    /** 从 (N, D) 向量矩阵建图。向量按给定顺序编号，第 i 行 → ordinal i。 */
    public static JVectorGraph build(float[][] matrix, int m, int efConstruction) {
        if (matrix.length == 0) {
            throw new IllegalArgumentException("向量矩阵为空");
        }
        int dim = matrix[0].length;
        List<VectorFloat<?>> list = new ArrayList<>(matrix.length);
        for (float[] row : matrix) {
            if (row.length != dim) {
                throw new IllegalArgumentException(
                        "第 " + list.size() + " 行维度是 " + row.length + "，期望 " + dim);
            }
            list.add(VTS.createFloatVector(row));
        }
        return buildFromList(list, dim, m, efConstruction);
    }

    private static JVectorGraph buildFromList(List<VectorFloat<?>> list, int dim,
                                              int m, int efConstruction) {
        RandomAccessVectorValues values = new ListRandomAccessVectorValues(list, dim);
        long t0 = System.currentTimeMillis();
        ImmutableGraphIndex graph;
        // GraphIndexBuilder 是 Closeable，建完必须关 —— 它持有建图期间的临时状态。
        // close() 关的是 builder 自己的东西，不影响已经 build() 出来的图（多字段连续建图已实测）。
        GraphIndexBuilder builder = new GraphIndexBuilder(
                values, SIMILARITY, m, efConstruction,
                1.2f,   // neighborOverflow：建图时多留邻居，提高连通性
                1.2f,   // alpha：剪枝强度
                true    // addHierarchy：分层（HNSW 的核心）
        );
        try {
            graph = builder.build(values);
        } finally {
            try {
                builder.close();
            } catch (IOException e) {
                // 图已经建好了，只是关建图器失败 —— 不必让整个建索引失败
                System.err.println("[建图] 关闭建图器失败（不影响已建好的图）: " + e.getMessage());
            }
        }
        System.err.printf("[建图] %,d 条 × %d 维  M=%d efC=%d  用时 %.1fs%n",
                list.size(), dim, m, efConstruction, (System.currentTimeMillis() - t0) / 1000.0);
        return new JVectorGraph(graph, values, null, dim, list.size());
    }

    // ------------------------------------------------------------------ 持久化

    /**
     * 把图写到磁盘（单文件）。
     *
     * <p>向量以 {@code InlineVectors} 特征**存在图文件里**，所以加载后不需要
     * 另外一份向量文件 —— 一个文件就是完整的图。
     *
     * <p>内部走 {@link OnDiskGraphIndex#write}，它会做一次
     * {@code sequentialRenumbering}。**没有删除节点时这个重编号是恒等的**，
     * 所以 ordinal 与建图时喂入的顺序一致，不会错位。
     *
     * <p>为什么必须持久化：HNSW 建图**并行且不可复现**（同一份数据多次建出的图
     * 略有差异，召回波动 ±0.4 个点）。生产上应当建一次、存下来、反复加载。
     *
     * <p>注意：这里没开量化特性（PQ/BQ）。向量本体占文件体积的 96% 以上，
     * 量化是压缩体积的最大杠杆，留给将来的 volume 优化。
     */
    public void save(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        long t0 = System.currentTimeMillis();
        OnDiskGraphIndex.write(graph, vectors, path);
        System.err.printf("[存图] %s  %.1f MB  用时 %.1fs%n",
                path, Files.size(path) / 1e6, (System.currentTimeMillis() - t0) / 1000.0);
    }

    /** 从磁盘加载图，并把向量载入堆（检索快）。文件由 {@link #save} 产出。 */
    public static JVectorGraph load(Path path) throws IOException {
        return load(path, true);
    }

    /**
     * 从磁盘加载图。
     *
     * @param cacheVectors 是否把向量从图文件载入堆。
     *
     *  <p><b>为什么需要这个开关</b>：磁盘图的 {@code View.getVector(node)}
     *  每次调用都会**新建一个维度大小的数组再从 mmap 读**，而图遍历每条 query 要
     *  访问几百个节点 —— 本机实测比向量在堆里慢约 19%（10 万 × 512，1.751 → 2.091 ms）。
     *  载入堆后检索路径与内存图完全一致。
     *
     *  <p>代价是内存：N × 维度 × 4 字节（10 万 × 512 约 204 MB）。**多字段时这个代价是乘字段数的**
     *  —— 3 个字段就是 612 MB。字段一多就该关掉，换回磁盘驻留（每张图只占约 5 MB 堆）。
     */
    public static JVectorGraph load(Path path, boolean cacheVectors) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("图文件不存在: " + path);
        }
        long t0 = System.currentTimeMillis();
        ReaderSupplier supplier = ReaderSupplierFactory.open(path);
        try {
            OnDiskGraphIndex graph = OnDiskGraphIndex.load(supplier);
            int dim = graph.getDimension();
            int size = graph.size();
            // View 同时实现了 RandomAccessVectorValues；图结构始终留在磁盘上
            RandomAccessVectorValues vectors = graph.getView();
            if (cacheVectors) {
                vectors = materialize(graph.getView(), size, dim);
            }
            System.err.printf("[载图] %,d 条 × %d 维，向量%s堆  用时 %.1fs%n",
                    size, dim, cacheVectors ? "载入" : "留在磁盘（未载入",
                    (System.currentTimeMillis() - t0) / 1000.0);
            return new JVectorGraph(graph, vectors, supplier, dim, size);
        } catch (Exception e) {
            supplier.close();   // 加载失败也要还回去，否则文件句柄泄漏
            throw e;
        }
    }

    /** 把磁盘图里的向量整份读进堆，换检索速度。 */
    private static RandomAccessVectorValues materialize(RandomAccessVectorValues view,
                                                        int size, int dim) {
        List<VectorFloat<?>> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(view.getVector(i));
        }
        return new ListRandomAccessVectorValues(list, dim);
    }

    // ------------------------------------------------------------------ 查

    /** 用默认候选池检索。 */
    public Hits search(VectorFloat<?> query, int topK) {
        return search(query, topK, DEFAULT_RERANK_K);
    }

    /**
     * 带 rerankK 的检索。
     *
     * @param rerankK 候选池大小（HNSW 的 efSearch / jvector 的 rerankK）。
     *                越大召回越高、越慢。传 &lt;= 0 时用 {@link #DEFAULT_RERANK_K}。
     *                **会自动夹到不小于 topK** —— ef 比 topK 小的话结果集根本凑不满。
     */
    public Hits search(VectorFloat<?> query, int topK, int rerankK) {
        // ef 必须 >= topK，否则候选池装不下要返回的条数，结果会凑不满
        int ef = Math.max(rerankK > 0 ? rerankK : DEFAULT_RERANK_K, topK);
        SearchResult result = GraphSearcher.search(
                query, topK, ef, vectors, SIMILARITY, graph, Bits.ALL);

        SearchResult.NodeScore[] nodes = result.getNodes();
        int[] ordinals = new int[nodes.length];
        float[] scores = new float[nodes.length];
        for (int i = 0; i < nodes.length; i++) {
            ordinals[i] = nodes[i].node;
            scores[i] = nodes[i].score;
        }
        return new Hits(ordinals, scores);
    }

    /** 把 float[] 转成 jvector 的向量类型，供批量检索复用。 */
    public static VectorFloat<?> toVector(float[] v) {
        return VTS.createFloatVector(v);
    }

    public int size() {
        return size;
    }

    public int dimension() {
        return dim;
    }

    @Override
    public void close() {
        try {
            graph.close();
        } catch (Exception ignored) {
            // 关闭失败不阻塞退出
        }
        // OnDiskGraphIndex.close() 是空实现，注释写着 "caller is responsible for
        // closing ReaderSupplier" —— 不在这里关就是文件句柄泄漏。
        if (supplier != null) {
            try {
                supplier.close();
            } catch (Exception ignored) {
                // 同上
            }
        }
    }
}
