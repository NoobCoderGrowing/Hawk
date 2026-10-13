package hawk.recall.search;

import java.util.List;

/**
 * 一整个布尔查询的执行体：把 {@code MUST / FILTER / SHOULD / MUST_NOT} 四种角色
 * 合成一个迭代器。
 *
 * <pre>
 *   候选集  = required 的交集（有 MUST/FILTER 时）
 *             否则 = optional 的并集
 *   分数    = required 中 MUST 的分数之和 + optional 命中的分数之和
 *   过滤    = SHOULD 命中数 ≥ minShouldMatch
 *   排除    = 减去 MUST_NOT 命中的
 * </pre>
 *
 * <p>与旧实现的关键差别：那边每种子句都要**先物化完整结果**再组合，
 * 这里全程是游标，用到哪条算哪条。
 */
public final class BooleanScorer extends Scorer {

    private final ConjunctionScorer required;      // MUST + FILTER；没有则 null
    private final DisjunctionScorer optional;      // SHOULD；没有则 null
    private final List<Scorer> excluded;           // MUST_NOT
    private final int minShouldMatch;

    private int docId = -1;
    private float score;

    public BooleanScorer(ConjunctionScorer required, DisjunctionScorer optional,
                         List<Scorer> excluded, int minShouldMatch) {
        if (required == null && optional == null) {
            throw new IllegalArgumentException("布尔查询至少要有一个正向子句");
        }
        this.required = required;
        this.optional = optional;
        this.excluded = List.copyOf(excluded);
        this.minShouldMatch = minShouldMatch;
    }

    @Override
    public int docId() {
        return docId;
    }

    private int advanceLead() {
        return required != null ? required.nextDoc() : optional.nextDoc();
    }

    @Override
    public int nextDoc() {
        return alignFrom(advanceLead());
    }

    /** 领头已经在 d 上，从这里往下对齐 SHOULD / 排除 minShouldMatch / 应用 MUST_NOT。 */
    private int alignFrom(int d) {
        outer:
        while (d != NO_MORE_DOCS) {
            // SHOULD：推进到 d，顺便拿到"命中几个"和"加多少分"
            float optionalScore = 0f;
            int optionalHits = 0;
            if (optional != null) {
                int od = optional.advance(d);
                if (od == d) {
                    optionalScore = optional.score();
                    optionalHits = optional.matchCount();
                }
            }

            // minShouldMatch：命中数不够就跳过这条
            if (optional != null && minShouldMatch > 0 && optionalHits < minShouldMatch) {
                d = advanceLead();
                continue;
            }

            // MUST_NOT：命中任一条就跳过
            for (Scorer ex : excluded) {
                if (ex.advance(d) == d) {
                    d = advanceLead();
                    continue outer;
                }
            }

            score = (required != null ? required.score() : 0f) + optionalScore;
            docId = d;
            return d;
        }
        docId = NO_MORE_DOCS;
        score = 0f;
        return docId;
    }

    @Override
    public int advance(int target) {
        if (docId >= target) {
            return docId;
        }
        // 领头**落到** target 上再对齐 —— 不能再调 nextDoc()，那会跳过正好在 target 上的那条
        int d = (required != null) ? required.advance(target) : optional.advance(target);
        return alignFrom(d);
    }

    @Override
    public float score() {
        return score;
    }

    @Override
    public long cost() {
        if (required != null) {
            return required.cost();
        }
        return optional.cost();
    }
}
