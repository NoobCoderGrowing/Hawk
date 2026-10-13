package hawk.recall.search;

import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 并集（SHOULD）：最小堆合并，各子句停在同一个 docID 时把分数**相加**。
 *
 * <p>顺带数出"这条文档命中了几个子句"（{@link #matchCount()}）——
 * {@code minShouldMatch} 要用它。旧实现里这个信息是拿不到的（分数被
 * {@code HashSet} 去重丢掉了），所以"至少命中 2 个词"根本表达不了。
 */
public final class DisjunctionScorer extends Scorer {

    private final List<Scorer> subs;
    /** 小顶堆：堆顶是当前 docID 最小的子句。 */
    private final PriorityQueue<Scorer> heap;
    private boolean initialized;
    private int docId = -1;
    private float score;
    private int matchCount;

    public DisjunctionScorer(List<Scorer> subs) {
        if (subs.isEmpty()) {
            throw new IllegalArgumentException("DisjunctionScorer 至少要有一个子句");
        }
        this.subs = List.copyOf(subs);
        this.heap = new PriorityQueue<>(Math.max(1, subs.size()),
                Comparator.comparingInt(Scorer::docId));
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;
        for (Scorer s : subs) {
            if (s.nextDoc() != NO_MORE_DOCS) {
                heap.offer(s);
            }
        }
    }

    @Override
    public int docId() {
        return docId;
    }

    /** 当前文档**命中了几个** SHOULD 子句。每次 nextDoc/advance 后都有效。 */
    public int matchCount() {
        return matchCount;
    }

    @Override
    public int nextDoc() {
        ensureInitialized();
        if (heap.isEmpty()) {
            docId = NO_MORE_DOCS;
            score = 0f;
            matchCount = 0;
            return docId;
        }
        int min = heap.peek().docId();
        float sum = 0f;
        int hits = 0;
        // 把所有停在这个 docID 上的子句都取出来：分数相加、各自再往前推一格
        while (!heap.isEmpty() && heap.peek().docId() == min) {
            Scorer s = heap.poll();
            sum += s.score();
            hits++;
            if (s.nextDoc() != NO_MORE_DOCS) {
                heap.offer(s);
            }
        }
        docId = min;
        score = sum;
        matchCount = hits;
        return docId;
    }

    @Override
    public float score() {
        return score;
    }

    @Override
    public long cost() {
        long sum = 0;
        for (Scorer s : subs) {
            sum += s.cost();
        }
        return sum;
    }
}
