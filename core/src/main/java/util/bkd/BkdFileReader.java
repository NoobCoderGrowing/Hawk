package util.bkd;

import directory.memory.MMap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BkdFileReader {

    private BkdFileReader() {
    }

    public static Map<String, BkdReader> open(Path bkdPath) throws IOException {
        if (!Files.exists(bkdPath) || Files.size(bkdPath) == 0) {
            return Collections.emptyMap();
        }
        MappedByteBuffer mapped = MMap.mmapFile(bkdPath.toString());
        mapped.load();
        ByteBuffer buffer = mapped.asReadOnlyBuffer();

        int magic = buffer.getInt();
        if (magic != BkdFormat.MAGIC) {
            throw new IllegalStateException("invalid .bkd magic: " + magic);
        }
        int formatVersion = buffer.getInt();
        if (formatVersion != BkdFormat.FORMAT_VERSION) {
            throw new IllegalStateException("unsupported .bkd format version: " + formatVersion);
        }
        int fieldCount = buffer.getInt();
        long directoryOffset = buffer.getLong();

        Map<String, BkdReader> readers = new HashMap<>();
        buffer.position((int) directoryOffset);
        for (int i = 0; i < fieldCount; i++) {
            int fieldLength = buffer.getInt();
            byte[] fieldBytes = new byte[fieldLength];
            buffer.get(fieldBytes);
            String fieldName = new String(fieldBytes, StandardCharsets.UTF_8);
            buffer.getInt();
            long rootOffset = buffer.getLong();
            readers.put(fieldName, new BkdReader(mapped, rootOffset));
        }
        return readers;
    }

    public static Map<String, List<BkdPoint>> readAllFieldPoints(Path bkdPath) throws IOException {
        Map<String, BkdReader> readers = open(bkdPath);
        Map<String, List<BkdPoint>> points = new HashMap<>();
        for (Map.Entry<String, BkdReader> entry : readers.entrySet()) {
            points.put(entry.getKey(), entry.getValue().readAllPoints());
        }
        return points;
    }
}
