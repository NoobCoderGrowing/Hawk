package hawk.recall.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 交集（MUST / FILTER）：**leapfrog**，只推进、不回头，零额外内存。
 *
 * <p>算法：拿代价最小的那个当领头，其余各自 {@code advance} 到领头的 docID；
 * 谁跳过头了，就让领头也跳到那个位置，重来。所有子句都停在同一个 docID 时，
 * 那个 docID 就是交集中的一条。
 *
 * <p>对比旧实现（物化各子结果 → 集合求交）：那边每个子结果都要全量进内存，
 * 这里只有 N 个游标。
 *
 * <p>分数 = **参与打分的子句**在该文档上的分数之和。哪些子句参与打分由构造时
 * 单独传进来 —— 因为 MUST 要算分、FILTER 不算，但两者都得参加交集。
 */
public final class ConjunctionScorer extends Scorer {

    /** 参加交集的全部子句，**按 cost 升序**（小的领头）。 */
    private final List<Scorer> all;
    /** 其中要算分的子集（即 MUST 子句）。可以为空 —— 那样整个交集分数为 0。 */
    private final List<Scorer> scoring;

    private int docId = -1;

    public ConjunctionScorer(List<Scorer> all, List<Scorer> scoring) {
        List<Scorer> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingLong(DocIdIterator::cost));
        this.all = List.copyOf(sorted);
        this.scoring = List.copyOf(scoring);
    }

    @Override
    public int docId() {
        return docId;
    }

    @Override
    public int nextDoc() {
        return alignFrom(all.get(0).nextDoc());
    }

    @Override
    public int advance(int target) {
        if (docId >= target) {
            return docId;
        }
        // 领头**落到** target 上，再从这里对齐。
        // 注意不能写成 all.get(0).advance(target) 之后调 nextDoc() ——
        // 那样会再把领头往前推一格，把正好落在 target 上的那条丢掉。
        return alignFrom(all.get(0).advance(target));
    }

    /** 从领头所在的位置 d 开始，逐个把其余子句对齐到 d。 */
    private int alignFrom(int d) {
        Scorer lead = all.get(0);
        while (d != NO_MORE_DOCS) {
            boolean aligned = true;
            for (int i = 1; i < all.size(); i++) {
                int other = all.get(i).advance(d);
                if (other > d) {
                    // 这个子句跳过了 d —— 领头跟上它，整轮重来
                    d = lead.advance(other);
                    aligned = false;
                    break;
                }
                // other == d：这个子句也命中 d，继续看下一个
            }
            if (!aligned) {
                continue;
            }
            docId = d;
            return d;
        }
        docId = NO_MORE_DOCS;
        return docId;
    }

    @Override
    public float score() {
        float sum = 0f;
        for (Scorer s : scoring) {
            sum += s.score();
        }
        return sum;
    }

    @Override
    public long cost() {
        // 交集最多只可能有领头那么多条
        return all.get(0).cost();
    }
}
