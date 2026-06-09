package hawk.recall.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TopScoreDocCollectorTest {

    @Test
    void keepsHighestScores() {
        TopScoreDocCollector collector = new TopScoreDocCollector(2);
        collector.collect(1f, 1);
        collector.collect(3f, 2);
        collector.collect(2f, 3);

        ScoreDoc[] hits = collector.topDocs();
        assertEquals(2, hits.length);
        assertEquals(3f, hits[0].score);
        assertEquals(2, hits[0].docID);
        assertEquals(2f, hits[1].score);
        assertEquals(3, hits[1].docID);
    }

    @Test
    void breaksTiesByDocIdDescending() {
        TopScoreDocCollector collector = new TopScoreDocCollector(3);
        for (int docId = 1; docId <= 5; docId++) {
            collector.collect(0f, docId);
        }

        ScoreDoc[] hits = collector.topDocs();
        assertEquals(3, hits.length);
        assertEquals(5, hits[0].docID);
        assertEquals(4, hits[1].docID);
        assertEquals(3, hits[2].docID);
    }

    @Test
    void returnsAllWhenHitCountLessThanTopN() {
        TopScoreDocCollector collector = new TopScoreDocCollector(5);
        collector.collect(1f, 10);
        collector.collect(2f, 20);

        ScoreDoc[] hits = collector.topDocs();
        assertEquals(2, hits.length);
        assertEquals(20, hits[0].docID);
        assertEquals(10, hits[1].docID);
    }

    @Test
    void topNZeroReturnsEmpty() {
        TopScoreDocCollector collector = new TopScoreDocCollector(0);
        collector.collect(1f, 1);
        assertArrayEquals(new ScoreDoc[0], collector.topDocs());
    }
}
