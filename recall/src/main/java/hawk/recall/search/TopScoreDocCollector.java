package hawk.recall.search;

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
        if (minHeap.size() < topN) {
            minHeap.offer(new ScoreDoc(score, docId));
            return;
        }
        ScoreDoc worst = minHeap.peek();
        if (score <= worst.score) {
            return;
        }
        minHeap.poll();
        minHeap.offer(new ScoreDoc(score, docId));
    }

    public boolean isSaturated(float score) {
        if (topN <= 0 || minHeap.size() < topN) {
            return false;
        }
        return score <= minHeap.peek().score;
    }

    public ScoreDoc[] topDocs() {
        if (minHeap.isEmpty()) {
            return new ScoreDoc[0];
        }
        return minHeap.toArray(new ScoreDoc[0]);
    }
}
