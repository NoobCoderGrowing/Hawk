package util.bkd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import util.NumberUtil;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BkdRoundtripTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripRandomPoints() throws Exception {
        Path bkdPath = tempDir.resolve("1.bkd");
        Random random = new Random(42);
        List<BkdPoint> expected = new ArrayList<>();
        for (int docId = 1; docId <= 5000; docId++) {
            double value = random.nextDouble() * 100.0;
            expected.add(new BkdPoint(docId, NumberUtil.double2SortableLong(value)));
        }

        Map<String, List<BkdPoint>> fieldPoints = new HashMap<>();
        fieldPoints.put("price", expected);
        BkdFileWriter.write(bkdPath, fieldPoints, new BkdConfig());

        BkdReader reader = BkdFileReader.open(bkdPath).get("price");
        long min = NumberUtil.double2SortableLong(10.0);
        long max = NumberUtil.double2SortableLong(20.0);
        int[] hits = reader.intersect(min, max);

        Set<Integer> expectedHits = new HashSet<>();
        for (BkdPoint point : expected) {
            if (point.getSortableValue() >= min && point.getSortableValue() <= max) {
                expectedHits.add(point.getDocId());
            }
        }
        Set<Integer> actualHits = new HashSet<>();
        for (int docId : hits) {
            actualHits.add(docId);
        }
        assertEquals(expectedHits, actualHits);
    }

    @Test
    void readAllPointsMatchesInput() throws Exception {
        Path bkdPath = tempDir.resolve("1.bkd");
        List<BkdPoint> points = new ArrayList<>();
        points.add(new BkdPoint(1, NumberUtil.double2SortableLong(1.5)));
        points.add(new BkdPoint(2, NumberUtil.double2SortableLong(99.0)));

        Map<String, List<BkdPoint>> fieldPoints = new HashMap<>();
        fieldPoints.put("price", points);
        BkdFileWriter.write(bkdPath, fieldPoints, new BkdConfig());

        List<BkdPoint> loaded = BkdFileReader.readAllFieldPoints(bkdPath).get("price");
        assertEquals(2, loaded.size());
        assertEquals(1, loaded.get(0).getDocId());
        assertEquals(NumberUtil.double2SortableLong(1.5), loaded.get(0).getSortableValue());
    }

    @Test
    void emptyRangeReturnsNoHits() throws Exception {
        Path bkdPath = tempDir.resolve("1.bkd");
        List<BkdPoint> points = new ArrayList<>();
        points.add(new BkdPoint(1, NumberUtil.double2SortableLong(50.0)));

        Map<String, List<BkdPoint>> fieldPoints = new HashMap<>();
        fieldPoints.put("price", points);
        BkdFileWriter.write(bkdPath, fieldPoints, new BkdConfig());

        BkdReader reader = BkdFileReader.open(bkdPath).get("price");
        int[] hits = reader.intersect(
                NumberUtil.double2SortableLong(1.0),
                NumberUtil.double2SortableLong(2.0));
        assertArrayEquals(new int[0], hits);
    }

    @Test
    void mergePointsOffsetsDocIds() {
        List<BkdPoint> left = new ArrayList<>();
        left.add(new BkdPoint(1, NumberUtil.double2SortableLong(1.0)));
        List<BkdPoint> right = new ArrayList<>();
        right.add(new BkdPoint(1, NumberUtil.double2SortableLong(2.0)));

        List<BkdPoint> merged = FieldBkdWriter.mergePoints(left, right, 10);
        assertEquals(2, merged.size());
        assertEquals(1, merged.get(0).getDocId());
        assertEquals(11, merged.get(1).getDocId());
    }

    @Test
    void intersectStopsAtLimit() throws Exception {
        Path bkdPath = tempDir.resolve("1.bkd");
        List<BkdPoint> points = new ArrayList<>();
        for (int docId = 1; docId <= 100; docId++) {
            points.add(new BkdPoint(docId, NumberUtil.double2SortableLong(docId)));
        }

        Map<String, List<BkdPoint>> fieldPoints = new HashMap<>();
        fieldPoints.put("price", points);
        BkdFileWriter.write(bkdPath, fieldPoints, new BkdConfig());

        BkdReader reader = BkdFileReader.open(bkdPath).get("price");
        List<Integer> collected = new ArrayList<>();
        reader.intersect(
                NumberUtil.double2SortableLong(1.0),
                NumberUtil.double2SortableLong(100.0),
                docId -> {
                    collected.add(docId);
                    return collected.size() < 10;
                });
        assertEquals(10, collected.size());
    }

    @Test
    void wideRangeMatchesBruteForceAfterSkipValueRead() throws Exception {
        Path bkdPath = tempDir.resolve("1.bkd");
        Random random = new Random(7);
        List<BkdPoint> expected = new ArrayList<>();
        for (int docId = 1; docId <= 2000; docId++) {
            double value = random.nextDouble() * 100.0;
            expected.add(new BkdPoint(docId, NumberUtil.double2SortableLong(value)));
        }

        Map<String, List<BkdPoint>> fieldPoints = new HashMap<>();
        fieldPoints.put("price", expected);
        BkdFileWriter.write(bkdPath, fieldPoints, new BkdConfig());

        BkdReader reader = BkdFileReader.open(bkdPath).get("price");
        long min = NumberUtil.double2SortableLong(1.0);
        long max = NumberUtil.double2SortableLong(100.0);
        int[] hits = reader.intersect(min, max);

        Set<Integer> expectedHits = new HashSet<>();
        for (BkdPoint point : expected) {
            if (point.getSortableValue() >= min && point.getSortableValue() <= max) {
                expectedHits.add(point.getDocId());
            }
        }
        Set<Integer> actualHits = new HashSet<>();
        for (int docId : hits) {
            actualHits.add(docId);
        }
        assertEquals(expectedHits, actualHits);
    }

    @Test
    void intersectStopsWhenTopNReached() throws Exception {
        Path bkdPath = tempDir.resolve("1.bkd");
        List<BkdPoint> points = new ArrayList<>();
        for (int docId = 1; docId <= 500; docId++) {
            points.add(new BkdPoint(docId, NumberUtil.double2SortableLong(docId)));
        }

        Map<String, List<BkdPoint>> fieldPoints = new HashMap<>();
        fieldPoints.put("price", points);
        BkdFileWriter.write(bkdPath, fieldPoints, new BkdConfig());

        BkdReader reader = BkdFileReader.open(bkdPath).get("price");
        int topN = 10;
        List<Integer> hits = new ArrayList<>();
        int[] visited = {0};
        reader.intersect(
                NumberUtil.double2SortableLong(1.0),
                NumberUtil.double2SortableLong(500.0),
                docId -> {
                    visited[0]++;
                    if (hits.size() < topN) {
                        hits.add(docId);
                    }
                    return hits.size() < topN;
                });

        assertEquals(topN, hits.size());
        assertTrue(visited[0] < 100, "expected early stop well before all 500 docs, visited=" + visited[0]);
    }
}
