package util.bkd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FieldBkdWriter {

    private final byte[] fieldName;

    private final List<BkdPoint> points = new ArrayList<>();

    public FieldBkdWriter(byte[] fieldName) {
        this.fieldName = fieldName;
    }

    public byte[] getFieldName() {
        return fieldName;
    }

    public void addPoint(int docId, long sortableValue) {
        points.add(new BkdPoint(docId, sortableValue));
    }

    public List<BkdPoint> getPoints() {
        return points;
    }

    public List<BkdPoint> copyPointsWithDocBase(int docBase) {
        if (docBase == 0) {
            return new ArrayList<>(points);
        }
        List<BkdPoint> copied = new ArrayList<>(points.size());
        for (BkdPoint point : points) {
            copied.add(new BkdPoint(point.getDocId() + docBase, point.getSortableValue()));
        }
        return copied;
    }

    public void clear() {
        points.clear();
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    public static List<BkdPoint> mergePoints(List<BkdPoint> left, List<BkdPoint> right, int rightDocBase) {
        List<BkdPoint> merged = new ArrayList<>(left.size() + right.size());
        merged.addAll(left);
        for (BkdPoint point : right) {
            merged.add(new BkdPoint(point.getDocId() + rightDocBase, point.getSortableValue()));
        }
        return merged;
    }

    /**
     * 按 sortable 值升序排 —— **有符号比较**，必须与 {@code BkdReader.intersectNode} 的剪枝一致。
     *
     * <h2>为什么是有符号</h2>
     * 建树时按什么序排，读的时候就得按同样的大小关系剪枝。
     * {@code intersectNode} 用的是 {@code nodeMax < minValue} 这类**有符号**比较，
     * 所以这里也必须是有符号。
     *
     * <p>⚠️ 这里曾经是 {@code Long.compareUnsigned} —— 那是配**旧的**数值编码的：
     * 旧编码把正数翻成负数（{@code b |= MIN_VALUE}）、负数翻成正数，
     * 恰好只有按无符号比才单调。而 {@code NumberUtil.double2SortableLong} 现在
     * 改成了**保符号**的编码（负数仍是负数），无符号比较会把正数排在负数之前，
     * 建出的树与剪枝逻辑互相矛盾 —— 表现为区间查询要么返回全部、要么返回空。
     */
    public static List<BkdPoint> sortPoints(List<BkdPoint> points) {
        List<BkdPoint> sorted = new ArrayList<>(points);
        Collections.sort(sorted, (a, b) -> {
            int cmp = Long.compare(a.getSortableValue(), b.getSortableValue());
            if (cmp != 0) {
                return cmp;
            }
            return Integer.compare(a.getDocId(), b.getDocId());
        });
        return sorted;
    }
}
