package hawk.recall.search;

import java.util.List;

/**
 * 一次检索的结果：**命中总数 + 前 N 条**。
 *
 * <p>旧实现只返回 {@code ScoreDoc[]}，拿不到总数，分页就得另想办法（再查一次、或者把
 * limit 开得很大）。总数是接口形状的一部分，越早带上越省事。
 *
 * <h2>为什么还要带 {@code totalExact}</h2>
 * 执行层为了性能会**提前终止**（例如已经凑够 top-K 且候选池饱和时）。
 * 那种情况下统计出来的总数只是**下界**，不是精确值。这个区别必须显式告诉调用方 ——
 * 否则"共 10000 条"被拿去算总页数，翻到后面全是空的，而且没有任何地方能看出为什么。
 *
 * @param totalHits  命中文档总数。{@code totalExact} 为 false 时它是**下界**
 * @param totalExact 上面那个数是不是精确值
 * @param scoreDocs  前 N 条，按分数降序（{@code ScoreMode.NO_SCORES} 时顺序不保证有意义）
 */
public record TopDocs(long totalHits, boolean totalExact, List<ScoreDoc> scoreDocs) {

    public static TopDocs empty() {
        return new TopDocs(0, true, List.of());
    }

    /** 这个结果是不是一条都没有。 */
    public boolean isEmpty() {
        return scoreDocs.isEmpty();
    }

    public int size() {
        return scoreDocs.size();
    }
}
