package hawk.recall.search;

/**
 * 给一个**不算分**的迭代器套上"分数恒为 0"的外壳。
 *
 * <p>存在的理由：执行层要统一形状。数值区间、MatchAll、MatchNone 都只出 docID，
 * 但它们同样要能放进 {@link DisjunctionScorer}/{@link ConjunctionScorer} 里参与组合 ——
 * 如果让"有的子句是 Scorer、有的不是"，组合器就要到处判类型。
 * 统一成 Scorer、分数为 0，组合逻辑就干净了。
 */
public final class ConstantScorer extends Scorer {

    private final DocIdIterator inner;
    private final float constant;

    public ConstantScorer(DocIdIterator inner, float constant) {
        this.inner = inner;
        this.constant = constant;
    }

    /** 一条都不匹配、分数为 0。 */
    public static ConstantScorer empty() {
        return new ConstantScorer(EmptyDocIdIterator.INSTANCE, 0f);
    }

    @Override
    public int docId() {
        return inner.docId();
    }

    @Override
    public int nextDoc() {
        return inner.nextDoc();
    }

    @Override
    public int advance(int target) {
        return inner.advance(target);
    }

    @Override
    public float score() {
        return constant;
    }

    @Override
    public long cost() {
        return inner.cost();
    }
}
