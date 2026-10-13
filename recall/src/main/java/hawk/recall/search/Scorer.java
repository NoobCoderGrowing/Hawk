package hawk.recall.search;

/**
 * 会打分的 {@link DocIdIterator}。
 *
 * <p>{@link #score()} 返回的是**当前文档**（{@link #docId()}）的分数，
 * 每次 nextDoc/advance 之后都有效。{@code ScoreMode.NO_SCORES} 时执行层压根不建
 * Scorer（用裸的 {@link DocIdIterator}），所以这里不必再判断"要不要算分"。
 *
 * <p>组合打分器（{@link ConjunctionScorer} / {@link DisjunctionScorer}）的分数是
 * **各命中子句之和** —— 与 Lucene 的布尔打分一致。
 */
public abstract class Scorer extends DocIdIterator {

    /** 当前文档的分数。 */
    public abstract float score();
}
