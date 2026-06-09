package util.bkd;

import util.DataInput;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class BkdReader {

    private final ByteBuffer buffer;

    private final long rootOffset;

    public BkdReader(ByteBuffer buffer, long rootOffset) {
        this.buffer = buffer.asReadOnlyBuffer();
        this.rootOffset = rootOffset;
    }

    @FunctionalInterface
    public interface DocIdCollector {
        /** @return true to continue traversal, false to stop early */
        boolean collect(int docId);
    }

    public int[] intersect(long minValue, long maxValue) {
        List<Integer> docIds = new ArrayList<>();
        intersect(minValue, maxValue, docId -> {
            docIds.add(docId);
            return true;
        });
        int[] result = new int[docIds.size()];
        for (int i = 0; i < docIds.size(); i++) {
            result[i] = docIds.get(i);
        }
        return result;
    }

    public void intersect(long minValue, long maxValue, DocIdCollector collector) {
        if (rootOffset < 0 || collector == null) {
            return;
        }
        intersectNode(rootOffset, minValue, maxValue, collector);
    }

    public List<BkdPoint> readAllPoints() {
        List<BkdPoint> points = new ArrayList<>();
        if (rootOffset >= 0) {
            collectPoints(rootOffset, points);
        }
        return points;
    }

    private boolean intersectNode(long offset, long minValue, long maxValue, DocIdCollector collector) {
        buffer.position((int) offset);
        byte nodeType = buffer.get();
        if (nodeType == BkdFormat.NODE_LEAF) {
            long nodeMin = readLong(buffer);
            long nodeMax = readLong(buffer);
            if (nodeMax < minValue || nodeMin > maxValue) {
                return true;
            }
            int count = buffer.getInt();
            boolean leafFullyInRange = nodeMin >= minValue && nodeMax <= maxValue;
            for (int i = 0; i < count; i++) {
                if (leafFullyInRange) {
                    buffer.position(buffer.position() + 8);
                    int docId = DataInput.readVint(buffer);
                    if (!collector.collect(docId)) {
                        return false;
                    }
                } else {
                    long value = readLong(buffer);
                    int docId = DataInput.readVint(buffer);
                    if (value >= minValue && value <= maxValue) {
                        if (!collector.collect(docId)) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        readLong(buffer);
        long nodeMin = readLong(buffer);
        long nodeMax = readLong(buffer);
        long leftOffset = readLong(buffer);
        long rightOffset = readLong(buffer);
        if (nodeMax < minValue || nodeMin > maxValue) {
            return true;
        }
        if (leftOffset >= 0 && !intersectNode(leftOffset, minValue, maxValue, collector)) {
            return false;
        }
        if (rightOffset >= 0 && !intersectNode(rightOffset, minValue, maxValue, collector)) {
            return false;
        }
        return true;
    }

    private void collectPoints(long offset, List<BkdPoint> points) {
        buffer.position((int) offset);
        byte nodeType = buffer.get();
        if (nodeType == BkdFormat.NODE_LEAF) {
            buffer.position((int) offset + 1 + 8 + 8);
            int count = buffer.getInt();
            for (int i = 0; i < count; i++) {
                long value = readLong(buffer);
                int docId = DataInput.readVint(buffer);
                points.add(new BkdPoint(docId, value));
            }
            return;
        }

        buffer.position((int) offset + 1 + 8 + 8 + 8);
        long leftOffset = readLong(buffer);
        long rightOffset = readLong(buffer);
        if (leftOffset >= 0) {
            collectPoints(leftOffset, points);
        }
        if (rightOffset >= 0) {
            collectPoints(rightOffset, points);
        }
    }

    private static long readLong(ByteBuffer buffer) {
        return buffer.getLong();
    }
}
