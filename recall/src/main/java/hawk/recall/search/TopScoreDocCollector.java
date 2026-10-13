package hawk.recall.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 收集器：一边数**总数**、一边只留前 K 条。
 *
 * <p>这是迭代器化之后自然需要的另一半 —— 执行层现在是"拉"的，得有人去拉。
 * 旧实现是"每个叶子先物化全量结果、再排序截断"，那时没有收集器这个概念。
 *
 * <h2>为什么要数总数</h2>
 * {@code limit=20} 只约束**返回几条**，不约束**一共命中多少**。分页要的是后者。
 * 旧实现返回裸数组，总数无从得知 —— 想要就得把 limit 开到极大再数一遍。
 *
 * <h2>为什么用堆而不是全收再排序</h2>
 * 全收再排序是 O(n log n) 且 O(n) 内存；有界小顶堆是 O(n log K) 且 O(K) 内存。
 * K 是 limit（通常几十），n 是命中数（可能几十万）—— 这个差别是数量级的。
 */
public final class TopScoreDocCollector {

    private final int topK;
    private final boolean byScore;
    /** 小顶堆：堆顶是"当前前 K 名里最差的那个"，大于它就进不来。 */
    private final PriorityQueue<ScoreDoc> heap;
    private long totalHits;

    /**
     * @param topK    最多保留几条；&lt;= 0 表示只数总数、不保留
     * @param byScore true 按分数排名；false 按 docID 升序（{@code ScoreMode.NO_SCORES} 时用）
     */
    public TopScoreDocCollector(int topK, boolean byScore) {
        this.topK = Math.max(topK, 0);
        this.byScore = byScore;
        Comparator<ScoreDoc> worstFirst = byScore
                // 分数低的在堆顶；分数相同则 docID 大的算"更差"
                ? (a, b) -> a.score() != b.score()
                        ? Float.compare(a.score(), b.score())
                        : Integer.compare(b.docId(), a.docId())
                // 不算分时按 docID：docID 大的在堆顶（更差）
                : (a, b) -> Integer.compare(b.docId(), a.docId());
        this.heap = new PriorityQueue<>(Math.max(1, this.topK), worstFirst);
    }

    public void collect(int docId, float score) {
        totalHits++;
        if (topK == 0) {
            return;
        }
        ScoreDoc hit = new ScoreDoc(docId, score);
        if (heap.size() < topK) {
            heap.offer(hit);
            return;
        }
        if (isBetterThanWorst(hit)) {
            heap.poll();
            heap.offer(hit);
        }
    }

    private boolean isBetterThanWorst(ScoreDoc hit) {
        ScoreDoc worst = heap.peek();
        return byScore ? hit.score() > worst.score() : hit.docId() < worst.docId();
    }

    /**
     * 提前退出判断：再来一条分数为 {@code score} 的文档，还进得了前 K 吗？
     *
     * <p>进不了就说明后面的也不用看了 —— 迭代器据此可以提前收工。
     */
    public boolean isSaturated(float score) {
        return topK > 0 && heap.size() >= topK && score <= heap.peek().score();
    }

    /** 收集完毕，产出结果。总数是精确的（迭代器跑完了全程）。 */
    public TopDocs topDocs() {
        List<ScoreDoc> out = new ArrayList<>(heap);
        if (byScore) {
            out.sort((a, b) -> a.score() != b.score()
                    ? Float.compare(b.score(), a.score())
                    : Integer.compare(a.docId(), b.docId()));
        } else {
            out.sort(Comparator.comparingInt(ScoreDoc::docId));
        }
        return new TopDocs(totalHits, true, out);
    }
}
