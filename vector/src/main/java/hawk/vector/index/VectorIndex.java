package hawk.vector.index;

import io.github.jbellis.jvector.util.Bits;

import java.io.Closeable;
import java.nio.file.Path;

/**
 * 向量索引契约。
 *
 * <h2>这个接口定义的是解耦边界</h2>
 *
 * <p><b>参数是 {@code float[]}，不是文本，也不是任何模型类型。</b>
 * 索引只回答「离这个向量最近的 top-K 是谁」，完全不关心向量是怎么来的 ——
 * 是 bge 编的、是别的模型编的、还是合成数据，对它都一样。
 *
 * <p>正因为如此，索引才可能被别的东西复用、才可能用合成向量独立测试。
 * 一旦这个签名里出现任何模型概念，这层边界就没了。
 * 详见 {@code com.hawkvector.index} 的包说明。
 *
 * <p>「模型和索引是否匹配」（维度、权重指纹）由**调用方**校验，
 * 不是本层的职责 —— 本层也拿不到这个信息。
 *
 * <h2>实现可替换</h2>
 *
 * jvector 是第一个实现，将来若要换 Lucene（比如需要向量 + BM25 同引擎混合召回），
 * 换一个实现类即可，调用方不动。
 */
public interface VectorIndex extends Closeable {

    /** 索引里的向量条数。 */
    int size();

    /** 向量维度。 */
    int dimension();

    /** 不带过滤的 top-K。 */
    default SearchHits search(float[] query, int topK) {
        return search(query, topK, Bits.ALL);
    }

    /**
     * 带过滤的 top-K。
     *
     * @param query       query 向量，必须与建索引时用的向量同维、同空间
     * @param acceptOrds  位图；第 i 位为 true 表示第 i 个向量**可以出现在结果里**。
     *                    语义是「在这些人里取 top-K」，不是「先取 top-K 再筛」——
     *                    见 {@code Bits} 的说明。不过滤传 {@link Bits#ALL}。
     */
    SearchHits search(float[] query, int topK, Bits acceptOrds);


    public static void main(String[] args) {
        try {
            float[][] matrix = NpyReader.readFloatMatrix(Path.of("work/zero_shot_bge_small/corpus_emb.npy"),10000);
            long[] ids = NpyReader.readLongArray(Path.of("work/zero_shot_bge_small/corpus_emb_ids.npy"));
            JVectorIndex index = JVectorIndex.build(matrix);
            // SearchHits hits = index.search(new float[] {0.1f, 0.2f, 0.3f}, 10);
        } catch (Exception e) {
            System.out.println("read item index failed");
        }
        

    }
}
