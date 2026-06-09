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

    public static List<BkdPoint> sortPoints(List<BkdPoint> points) {
        List<BkdPoint> sorted = new ArrayList<>(points);
        Collections.sort(sorted, (a, b) -> {
            int cmp = Long.compareUnsigned(a.getSortableValue(), b.getSortableValue());
            if (cmp != 0) {
                return cmp;
            }
            return Integer.compare(a.getDocId(), b.getDocId());
        });
        return sorted;
    }
}
