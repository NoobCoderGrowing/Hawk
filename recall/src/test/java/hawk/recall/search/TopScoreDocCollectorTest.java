package hawk.recall.search;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopScoreDocCollectorTest {

    @Test
    void keepsHighestScores() {
        TopScoreDocCollector collector = new TopScoreDocCollector(2);
        collector.collect(1f, 1);
        collector.collect(3f, 2);
        collector.collect(2f, 3);

        ScoreDoc[] hits = collector.topDocs();
        assertEquals(2, hits.length);
        assertEquals(Set.of(2, 3), docIds(hits));
        assertEquals(Set.of(2f, 3f), scores(hits));
    }

    @Test
    void keepsFirstDocsWhenScoresEqual() {
        TopScoreDocCollector collector = new TopScoreDocCollector(3);
        for (int docId = 1; docId <= 5; docId++) {
            collector.collect(0f, docId);
        }

        ScoreDoc[] hits = collector.topDocs();
        assertEquals(3, hits.length);
        assertEquals(Set.of(1, 2, 3), docIds(hits));
    }

    @Test
    void returnsAllWhenHitCountLessThanTopN() {
        TopScoreDocCollector collector = new TopScoreDocCollector(5);
        collector.collect(1f, 10);
        collector.collect(2f, 20);

        ScoreDoc[] hits = collector.topDocs();
        assertEquals(2, hits.length);
        assertEquals(Set.of(10, 20), docIds(hits));
        assertEquals(Set.of(1f, 2f), scores(hits));
    }

    @Test
    void topNZeroReturnsEmpty() {
        TopScoreDocCollector collector = new TopScoreDocCollector(0);
        collector.collect(1f, 1);
        assertArrayEquals(new ScoreDoc[0], collector.topDocs());
    }

    @Test
    void rejectsEqualScoreAfterHeapFull() {
        TopScoreDocCollector collector = new TopScoreDocCollector(2);
        collector.collect(1f, 1);
        collector.collect(1f, 2);
        collector.collect(1f, 3);

        ScoreDoc[] hits = collector.topDocs();
        assertEquals(2, hits.length);
        assertEquals(Set.of(1, 2), docIds(hits));
    }

    @Test
    void replacesWhenScoreIsHigher() {
        TopScoreDocCollector collector = new TopScoreDocCollector(2);
        collector.collect(1f, 1);
        collector.collect(1f, 2);
        collector.collect(2f, 3);

        ScoreDoc[] hits = collector.topDocs();
        assertEquals(2, hits.length);
        assertTrue(docIds(hits).contains(3));
        assertTrue(scores(hits).contains(2f));
    }

    @Test
    void isSaturatedFalseUntilHeapFull() {
        TopScoreDocCollector collector = new TopScoreDocCollector(3);
        assertTrue(!collector.isSaturated(0f));
        collector.collect(0f, 1);
        assertTrue(!collector.isSaturated(0f));
        collector.collect(0f, 2);
        assertTrue(!collector.isSaturated(0f));
        collector.collect(0f, 3);
        assertTrue(collector.isSaturated(0f));
    }

    @Test
    void isSaturatedFalseWhenHigherScoreCouldEnter() {
        TopScoreDocCollector collector = new TopScoreDocCollector(2);
        collector.collect(1f, 1);
        collector.collect(2f, 2);
        assertTrue(!collector.isSaturated(3f));
        assertTrue(collector.isSaturated(2f));
        assertTrue(collector.isSaturated(1f));
    }

    private static Set<Integer> docIds(ScoreDoc[] hits) {
        return Arrays.stream(hits).map(hit -> hit.docID).collect(Collectors.toCollection(HashSet::new));
    }

    private static Set<Float> scores(ScoreDoc[] hits) {
        return Arrays.stream(hits).map(hit -> hit.score).collect(Collectors.toCollection(HashSet::new));
    }
}
