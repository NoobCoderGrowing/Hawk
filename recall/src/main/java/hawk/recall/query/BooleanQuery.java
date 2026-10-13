package hawk.recall.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 布尔查询：一组子句 + 每个子句的角色（{@link Occur}），外加 SHOULD 的最低命中数。
 *
 * <p>不可变。要拼装用 {@link #builder()}。
 *
 * <pre>
 *   BooleanQuery q = BooleanQuery.builder()
 *           .must(TermQuery.of("title", "冰糖"))
 *           .filter(RangeQuery.closed("price", 10, 100))
 *           .mustNot(TermQuery.of("title", "儿童"))
 *           .should(TermQuery.of("title", "老黄"))
 *           .build();
 * </pre>
 *
 * @param clauses       子句。**不能为空**
 * @param minShouldMatch 至少要命中几个 SHOULD 子句才算出结果。
 *        {@code 0} 表示"不额外要求"，但若整个查询**只有** SHOULD 子句，
 *        执行层会按 1 处理（否则整个查询等价于 MatchAll —— 那不是调用方想要的）。
 */
public record BooleanQuery(List<Clause> clauses, int minShouldMatch) implements Query {

    /** 一个子句：查询 + 它的角色。 */
    public record Clause(Query query, Occur occur) {
        public Clause {
            Objects.requireNonNull(query, "子句的 query 不能为空");
            Objects.requireNonNull(occur, "子句的 occur 不能为空");
        }
    }

    public BooleanQuery {
        clauses = List.copyOf(clauses);
        if (clauses.isEmpty()) {
            throw new IllegalArgumentException("布尔查询至少要有一个子句");
        }
        if (minShouldMatch < 0) {
            throw new IllegalArgumentException("minShouldMatch 不能为负：" + minShouldMatch);
        }
        long shouldCount = clauses.stream().filter(c -> c.occur() == Occur.SHOULD).count();
        if (minShouldMatch > shouldCount) {
            throw new IllegalArgumentException(
                    "minShouldMatch=" + minShouldMatch + " 超过了 SHOULD 子句数 " + shouldCount);
        }
        // 只有 MUST_NOT 的话，没有任何子句能提供候选集 —— 一条都匹配不到。
        // 与其静默返回空，不如让它早失败，并在报错里给出出路（配一个 MatchAll）。
        boolean hasPositive = clauses.stream().anyMatch(c -> c.occur() != Occur.MUST_NOT);
        if (!hasPositive) {
            throw new IllegalArgumentException(
                    "布尔查询至少要有一个正向子句（MUST / FILTER / SHOULD）。"
                            + "若只想做排除，请配一个 MatchAllQuery 当底座："
                            + "must(MatchAllQuery.INSTANCE).mustNot(...)");
        }
    }

    /** 这个查询是否含至少一个会打分的子句（MUST / SHOULD）。 */
    public boolean hasScoringClause() {
        return clauses.stream().anyMatch(c -> c.occur().scores());
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 拼装器。所有 {@code addXxx} 返回 {@code this}，最后 {@link #build()}。 */
    public static final class Builder {
        private final List<Clause> clauses = new ArrayList<>();
        private int minShouldMatch = 0;

        public Builder add(Query query, Occur occur) {
            clauses.add(new Clause(query, occur));
            return this;
        }

        public Builder must(Query query) {
            return add(query, Occur.MUST);
        }

        public Builder filter(Query query) {
            return add(query, Occur.FILTER);
        }

        public Builder should(Query query) {
            return add(query, Occur.SHOULD);
        }

        public Builder mustNot(Query query) {
            return add(query, Occur.MUST_NOT);
        }

        public Builder minShouldMatch(int n) {
            this.minShouldMatch = n;
            return this;
        }

        public BooleanQuery build() {
            return new BooleanQuery(clauses, minShouldMatch);
        }
    }
}
