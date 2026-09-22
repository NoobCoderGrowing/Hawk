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
 */
public class Demo {

    private static final int LIMIT = 10000;
    public static void main(String[] args) throws Exception {
        // 读取商品向量，读取向量->商品真实id映射
        float[][] matrix = NpyReader.readFloatMatrix(Path.of("model/zero_shot_bge_small/corpus_emb.npy"), LIMIT);
        long[] ids = NpyReader.readLongArray(Path.of("model/zero_shot_bge_small/corpus_emb_ids.npy"), LIMIT);
        System.out.printf("商品向量 %d × %d%n", matrix.length, matrix[0].length);

        // 建索引
        try (JVectorIndex index = JVectorIndex.build(matrix)) {
            try (TextEncoder encoder = new TextEncoder(Path.of("model/bge-small-zh-v1.5"))) {
                String text = "大宝护手霜";
                float[] query = encoder.encodeOne(text, Role.QUERY);

                // 检索
                SearchHits hits = index.search(query, 10, Bits.ALL);

                // 打印命中商品排序，相似度，以及商品真实id
                System.out.printf("查询「%s」top-%d：%n", text, hits.size());
                int[] ordinals = hits.ordinals();
                long[] hitsIDs = new long[ordinals.length]; 
                for (int i = 0; i < ordinals.length; i++) {
                    hitsIDs[i] = ids[ordinals[i]];
                    System.out.printf("  #%d  %.4f  ordinal=%-6d 商品ID=%d%n",
                            i + 1, hits.scores()[i], ordinals[i], hitsIDs[i]);
                }
            }
        }
    }
}
