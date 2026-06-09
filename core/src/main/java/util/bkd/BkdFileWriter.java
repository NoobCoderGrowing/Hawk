package util.bkd;

import util.DataOutput;
import util.NumberUtil;
import util.WrapLong;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class BkdFileWriter {

    private BkdFileWriter() {
    }

    public static void write(Path bkdPath, Map<String, List<BkdPoint>> fieldPoints, BkdConfig config) throws IOException {
        List<FieldEntry> entries = new ArrayList<>();
        for (Map.Entry<String, List<BkdPoint>> entry : fieldPoints.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            entries.add(new FieldEntry(entry.getKey(), FieldBkdWriter.sortPoints(entry.getValue())));
        }
        entries.sort(Comparator.comparing(FieldEntry::getFieldName));

        try (FileChannel channel = new RandomAccessFile(bkdPath.toFile(), "rw").getChannel()) {
            WrapLong pos = new WrapLong(0);
            DataOutput.writeInt(BkdFormat.MAGIC, channel, pos);
            DataOutput.writeInt(BkdFormat.FORMAT_VERSION, channel, pos);
            DataOutput.writeInt(entries.size(), channel, pos);
            long directoryOffsetPos = pos.getValue();
            DataOutput.writeLong(0L, channel, pos);

            List<FieldMeta> metas = new ArrayList<>(entries.size());
            for (FieldEntry entry : entries) {
                long rootOffset = writeTree(channel, pos, entry.getPoints(), config.getMaxPointsInLeaf());
                metas.add(new FieldMeta(entry.getFieldName(), entry.getPoints().size(), rootOffset));
            }

            long directoryOffset = pos.getValue();
            for (FieldMeta meta : metas) {
                byte[] fieldBytes = meta.fieldName.getBytes(StandardCharsets.UTF_8);
                DataOutput.writeInt(fieldBytes.length, channel, pos);
                DataOutput.writeBytes(fieldBytes, channel, pos);
                DataOutput.writeInt(meta.numPoints, channel, pos);
                DataOutput.writeLong(meta.rootOffset, channel, pos);
            }

            channel.write(ByteBuffer.wrap(NumberUtil.long2Bytes(directoryOffset)), directoryOffsetPos);
            channel.force(false);
        }
    }

    private static long writeTree(FileChannel channel, WrapLong pos, List<BkdPoint> points, int maxPointsInLeaf)
            throws IOException {
        if (points.isEmpty()) {
            return -1L;
        }
        long minValue = points.get(0).getSortableValue();
        long maxValue = points.get(points.size() - 1).getSortableValue();
        if (points.size() <= maxPointsInLeaf) {
            long leafOffset = pos.getValue();
            DataOutput.writeByte(BkdFormat.NODE_LEAF, channel, pos);
            DataOutput.writeBytes(NumberUtil.long2Bytes(minValue), channel, pos);
            DataOutput.writeBytes(NumberUtil.long2Bytes(maxValue), channel, pos);
            DataOutput.writeInt(points.size(), channel, pos);
            for (BkdPoint point : points) {
                DataOutput.writeBytes(NumberUtil.long2Bytes(point.getSortableValue()), channel, pos);
                DataOutput.writeVInt(point.getDocId(), channel, pos);
            }
            return leafOffset;
        }

        int mid = points.size() / 2;
        long splitValue = points.get(mid).getSortableValue();
        List<BkdPoint> left = new ArrayList<>(points.subList(0, mid));
        List<BkdPoint> right = new ArrayList<>(points.subList(mid, points.size()));

        long nodeOffset = pos.getValue();
        DataOutput.writeByte(BkdFormat.NODE_INNER, channel, pos);
        DataOutput.writeBytes(NumberUtil.long2Bytes(splitValue), channel, pos);
        DataOutput.writeBytes(NumberUtil.long2Bytes(minValue), channel, pos);
        DataOutput.writeBytes(NumberUtil.long2Bytes(maxValue), channel, pos);
        long leftOffsetPos = pos.getValue();
        DataOutput.writeLong(0L, channel, pos);
        long rightOffsetPos = pos.getValue();
        DataOutput.writeLong(0L, channel, pos);

        long leftOffset = writeTree(channel, pos, left, maxPointsInLeaf);
        long rightOffset = writeTree(channel, pos, right, maxPointsInLeaf);

        channel.write(ByteBuffer.wrap(NumberUtil.long2Bytes(leftOffset)), leftOffsetPos);
        channel.write(ByteBuffer.wrap(NumberUtil.long2Bytes(rightOffset)), rightOffsetPos);
        return nodeOffset;
    }

    private static final class FieldEntry {
        private final String fieldName;
        private final List<BkdPoint> points;

        private FieldEntry(String fieldName, List<BkdPoint> points) {
            this.fieldName = fieldName;
            this.points = points;
        }

        private String getFieldName() {
            return fieldName;
        }

        private List<BkdPoint> getPoints() {
            return points;
        }
    }

    private static final class FieldMeta {
        private final String fieldName;
        private final int numPoints;
        private final long rootOffset;

        private FieldMeta(String fieldName, int numPoints, long rootOffset) {
            this.fieldName = fieldName;
            this.numPoints = numPoints;
            this.rootOffset = rootOffset;
        }
    }
}
