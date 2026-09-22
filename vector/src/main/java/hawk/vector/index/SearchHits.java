package hawk.vector.index;

/**
 * 检索结果：ordinal 与分数的平行数组，按分数降序。
 *
 * <p><b>ordinal 是向量在索引里的行号</b>，不是业务主键。业务主键要另外映射 ——
 * 建索引时按什么顺序喂入向量，ordinal 就是什么顺序。
 */
public record SearchHits(int[] ordinals, float[] scores) {

    public int size() {
        return ordinals.length;
    }

    /** 该 ordinal 在结果里的名次（1-based）；不在结果里返回 0。 */
    public int rankOf(int ordinal) {
        for (int i = 0; i < ordinals.length; i++) {
            if (ordinals[i] == ordinal) {
                return i + 1;
            }
        }
        return 0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ordinals.length; i++) {
            sb.append(String.format("  #%d  %.4f  ord=%d%n", i + 1, scores[i], ordinals[i]));
        }
        return sb.toString();
    }
}
