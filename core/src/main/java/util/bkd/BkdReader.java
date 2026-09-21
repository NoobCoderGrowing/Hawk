package util.bkd;

import util.DataInput;
import util.WrapLong;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class BkdReader {

    /** .bkd 里的 Int/Long 都是 big-endian（见 INDEX_FORMAT §12）。
     *
     *  两个 FFM 坑与 MMapDirectoryReader.JAVA_INT_BE 同因：
     *  1) 字节序——ValueLayout.JAVA_INT/JAVA_LONG 默认用 ByteOrder.nativeOrder()（x86 小端），
     *     而格式是大端，直接用会静默读出错值。
     *  2) 对齐——节点紧凑排布，`Byte nodeType` 后紧跟 `Long`（落在偏移 1），
     *     默认的对齐约束会抛 IllegalArgumentException，必须 withByteAlignment(1)。 */
    private static final ValueLayout.OfInt JAVA_INT_BE =
            ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN).withByteAlignment(1);
    private static final ValueLayout.OfLong JAVA_LONG_BE =
            ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN).withByteAlignment(1);

    private final MemorySegment segment;

    private final long rootOffset;

    public BkdReader(MemorySegment segment, long rootOffset) {
        this.segment = segment.asReadOnly();
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
        long pos = offset;
        byte nodeType = segment.get(ValueLayout.JAVA_BYTE, pos);
        if (nodeType == BkdFormat.NODE_LEAF) {
            pos += 1;
            long nodeMin = segment.get(JAVA_LONG_BE, pos);
            pos += 8;
            long nodeMax = segment.get(JAVA_LONG_BE, pos);
            pos += 8;
            if (nodeMax < minValue || nodeMin > maxValue) {
                return true;
            }
            int count = segment.get(JAVA_INT_BE, pos);
            pos += 4;
            boolean leafFullyInRange = nodeMin >= minValue && nodeMax <= maxValue;
            WrapLong cursor = new WrapLong(pos);
            for (int i = 0; i < count; i++) {
                if (leafFullyInRange) {
                    cursor.setValue(cursor.getValue() + 8);
                    int docId = DataInput.readVintAt(segment, cursor);
                    if (!collector.collect(docId)) {
                        return false;
                    }
                } else {
                    long value = segment.get(JAVA_LONG_BE, cursor.getValue());
                    cursor.setValue(cursor.getValue() + 8);
                    int docId = DataInput.readVintAt(segment, cursor);
                    if (value >= minValue && value <= maxValue) {
                        if (!collector.collect(docId)) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        // 内部节点: nodeType(1) + splitValue(8) + minValue(8) + maxValue(8) + left(8) + right(8)
        pos += 1 + 8;
        long nodeMin = segment.get(JAVA_LONG_BE, pos);
        pos += 8;
        long nodeMax = segment.get(JAVA_LONG_BE, pos);
        pos += 8;
        long leftOffset = segment.get(JAVA_LONG_BE, pos);
        pos += 8;
        long rightOffset = segment.get(JAVA_LONG_BE, pos);
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
        long pos = offset;
        byte nodeType = segment.get(ValueLayout.JAVA_BYTE, pos);
        if (nodeType == BkdFormat.NODE_LEAF) {
            pos = offset + 1 + 8 + 8;
            int count = segment.get(JAVA_INT_BE, pos);
            pos += 4;
            WrapLong cursor = new WrapLong(pos);
            for (int i = 0; i < count; i++) {
                long value = segment.get(JAVA_LONG_BE, cursor.getValue());
                cursor.setValue(cursor.getValue() + 8);
                int docId = DataInput.readVintAt(segment, cursor);
                points.add(new BkdPoint(docId, value));
            }
            return;
        }

        pos = offset + 1 + 8 + 8 + 8;
        long leftOffset = segment.get(JAVA_LONG_BE, pos);
        pos += 8;
        long rightOffset = segment.get(JAVA_LONG_BE, pos);
        if (leftOffset >= 0) {
            collectPoints(leftOffset, points);
        }
        if (rightOffset >= 0) {
            collectPoints(rightOffset, points);
        }
    }
}
