package util.bkd;

import directory.memory.MMap;
import util.DataInput;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BkdFileReader {

    /** 见 {@link BkdReader}：.bkd 的 Int/Long 是大端，且节点紧凑排布需关掉对齐约束。 */
    private static final ValueLayout.OfInt JAVA_INT_BE =
            ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN).withByteAlignment(1);
    private static final ValueLayout.OfLong JAVA_LONG_BE =
            ValueLayout.JAVA_LONG.withOrder(ByteOrder.BIG_ENDIAN).withByteAlignment(1);

    private BkdFileReader() {
    }

    /** 映射 .bkd；映射的生命周期由调用方传入的 {@code arena} 持有。 */
    public static Map<String, BkdReader> open(Path bkdPath, Arena arena) throws IOException {
        if (!Files.exists(bkdPath) || Files.size(bkdPath) == 0) {
            return Collections.emptyMap();
        }
        MemorySegment mapped = MMap.mmapFile(bkdPath.toString(), arena);
        mapped.load();

        long pos = 0;
        int magic = mapped.get(JAVA_INT_BE, pos);
        pos += 4;
        if (magic != BkdFormat.MAGIC) {
            throw new IllegalStateException("invalid .bkd magic: " + magic);
        }
        int formatVersion = mapped.get(JAVA_INT_BE, pos);
        pos += 4;
        if (formatVersion != BkdFormat.FORMAT_VERSION) {
            throw new IllegalStateException("unsupported .bkd format version: " + formatVersion);
        }
        int fieldCount = mapped.get(JAVA_INT_BE, pos);
        pos += 4;
        long directoryOffset = mapped.get(JAVA_LONG_BE, pos);

        Map<String, BkdReader> readers = new HashMap<>();
        pos = directoryOffset;
        for (int i = 0; i < fieldCount; i++) {
            int fieldLength = mapped.get(JAVA_INT_BE, pos);
            pos += 4;
            byte[] fieldBytes = DataInput.readBytes(mapped, pos, fieldLength);
            pos += fieldLength;
            String fieldName = new String(fieldBytes, StandardCharsets.UTF_8);
            pos += 4; // numPoints，读取时用不到
            long rootOffset = mapped.get(JAVA_LONG_BE, pos);
            pos += 8;
            readers.put(fieldName, new BkdReader(mapped, rootOffset));
        }
        return readers;
    }

    /** 便捷重载：一次性把全部点读成 Java 对象后即可弃用映射，
     *  因此用局部 Arena 自管生命周期是安全的（{@link BkdReader#readAllPoints()} 不保留 segment 引用）。 */
    public static Map<String, List<BkdPoint>> readAllFieldPoints(Path bkdPath) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            return readAllFieldPoints(bkdPath, arena);
        }
    }

    public static Map<String, List<BkdPoint>> readAllFieldPoints(Path bkdPath, Arena arena) throws IOException {
        Map<String, BkdReader> readers = open(bkdPath, arena);
        Map<String, List<BkdPoint>> points = new HashMap<>();
        for (Map.Entry<String, BkdReader> entry : readers.entrySet()) {
            points.put(entry.getKey(), entry.getValue().readAllPoints());
        }
        return points;
    }
}
