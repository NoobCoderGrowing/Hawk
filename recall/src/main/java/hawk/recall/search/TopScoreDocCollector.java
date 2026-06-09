package hawk.recall.search;

import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;

public final class TopScoreDocCollector {

    private static final Comparator<ScoreDoc> MIN_HEAP_ORDER = (left, right) -> {
        if (left.score < right.score) {
            return -1;
        }
        if (left.score > right.score) {
            return 1;
        }
        return Integer.compare(left.docID, right.docID);
    };

    private static final Comparator<ScoreDoc> RESULT_ORDER = (left, right) -> {
        if (left.score > right.score) {
            return -1;
        }
        if (left.score < right.score) {
            return 1;
        }
        return Integer.compare(right.docID, left.docID);
    };

    private final int topN;

    private final PriorityQueue<ScoreDoc> minHeap;

    public TopScoreDocCollector(int topN) {
        this.topN = topN;
        this.minHeap = new PriorityQueue<>(Math.max(topN, 1), MIN_HEAP_ORDER);
    }

    public void collect(float score, int docId) {
        if (topN <= 0) {
            return;
        }
        ScoreDoc candidate = new ScoreDoc(score, docId);
        if (minHeap.size() < topN) {
            minHeap.offer(candidate);
            return;
        }
        ScoreDoc worst = minHeap.peek();
        if (isBetter(candidate, worst)) {
            minHeap.poll();
            minHeap.offer(candidate);
        }
    }

    public ScoreDoc[] topDocs() {
        if (minHeap.isEmpty()) {
            return new ScoreDoc[0];
        }
        ScoreDoc[] results = minHeap.toArray(new ScoreDoc[0]);
        Arrays.sort(results, RESULT_ORDER);
        return results;
    }

    private static boolean isBetter(ScoreDoc candidate, ScoreDoc currentWorst) {
        if (candidate.score > currentWorst.score) {
            return true;
        }
        return candidate.score == currentWorst.score && candidate.docID > currentWorst.docID;
    }
}
