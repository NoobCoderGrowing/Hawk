package hawk.vector.demo;

import hawk.vector.index.JVectorIndex;
import hawk.vector.index.NpyReader;
import hawk.vector.index.SearchHits;
import hawk.vector.model.ModelDescriptor.Role;
import hawk.vector.model.TextEncoder;

import io.github.jbellis.jvector.util.Bits;

import java.nio.file.Path;

/**
 * 端到端示例：商品向量 → 建索引 → query 编码 → 检索 → ordinal 映射回商品 ID。
 *
 * <p>串起前面搭好的每一块：{@link NpyReader}（读 Python 产出的向量与 ID 映射）
 * → {@link JVectorIndex}（建索引 / 检索）→ {@link TextEncoder}（文本 → 向量）。
 * 模型与索引是解耦的，把两者接起来是本类的职责。
 *
 * <p><b>商品库就是 hawk 主仓库的 {@code goods.csv}</b>：取它的 {@code title} 列编码成向量，
 * {@code id} 列（0-based，0..100000）作为商品 ID。因此这里 {@code ids[i] == i} ——
 * ordinal 本身就等于商品 ID，下面的 {@code ids[ordinal]} 查表在语义上仍是必要的，
 * 但结果与直接用 ordinal 相同。换成语料 ID 非顺序的数据集时，这一层映射就不能省。
 */
public class Demo {

    private static final String VECTOR_DIR = "model/zero_shot_bge_small";
    private static final String MODEL_DIR = "model/bge-small-zh-v1.5";

    public static void main(String[] args) throws Exception {
        // 查询词可由命令行覆盖；默认取 goods.csv 里真实存在的商品词
        String text = args.length > 0 ? args[0] : "老黄冰糖";
        int topK = args.length > 1 ? Integer.parseInt(args[1]) : 10;

        // 1) 读商品向量，以及 行号 → 商品 ID 的映射（全部 100,001 条）
        float[][] matrix = NpyReader.readFloatMatrix(Path.of(VECTOR_DIR, "corpus_emb.npy"));
        long[] ids = NpyReader.readLongArray(Path.of(VECTOR_DIR, "corpus_emb_ids.npy"));
        System.out.printf("商品向量 %d × %d%n", matrix.length, matrix[0].length);

        // 2) 建索引
        try (JVectorIndex index = JVectorIndex.build(matrix)) {
            // 3) 加载模型，把 query 文本编码成向量
            try (TextEncoder encoder = new TextEncoder(Path.of(MODEL_DIR))) {
                float[] query = encoder.encodeOne(text, Role.QUERY);

                // 4) 检索
                SearchHits hits = index.search(query, topK, Bits.ALL);

                // 5) 打印命中商品排序、相似度，以及商品真实 ID
                System.out.printf("查询「%s」top-%d：%n", text, hits.size());
                int[] ordinals = hits.ordinals();
                for (int i = 0; i < ordinals.length; i++) {
                    long productId = ids[ordinals[i]];
                    System.out.printf("  #%d  %.4f  ordinal=%-6d 商品ID=%d%n",
                            i + 1, hits.scores()[i], ordinals[i], productId);
                }
            }
        }
    }
}
