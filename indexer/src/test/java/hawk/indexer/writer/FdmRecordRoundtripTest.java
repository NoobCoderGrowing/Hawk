package hawk.indexer.writer;

import directory.memory.MMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import util.SegmentCursor;

import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code 1.fdm} 记录的**字节往返**测试。
 *
 * <h2>为什么需要它</h2>
 * fdm 是**跨模块的落盘约定**，同一份记录布局散在三处：
 * <ul>
 *   <li>写：{@code DocWriter.writeFDM}</li>
 *   <li>读：{@code MMapDirectoryReader.constructFdmMap}</li>
 *   <li><b>读+重写：{@link IndexMerger}</b>（段合并是逐条读出再重写，不是字节拷贝）</li>
 * </ul>
 * 三处必须逐字节对齐。漏改其中一处的表现是"字段属性读错了但没人报错" —— 正是本仓库最想避免的
 * 失败模式。加 tokenizer 那一段（格式版本 v3）时，合并侧就是最容易漏的那个。
 *
 * <p>本测试只覆盖 {@link IndexMerger} 这一侧的读写对称 + 合并规则；另两处由端到端用例覆盖
 * （建索引 → 读回 fdm → 检索）。
 */
class FdmRecordRoundtripTest {

    /** mmap 的生命周期由调用方管，与 BkdRoundtripTest 的取法一致。 */
    private final Arena arena = Arena.ofShared();

    @TempDir
    Path tempDir;

    @AfterEach
    void closeArena() {
        arena.close();
    }

    @Test
    void roundTripKeepsTokenizers() throws Exception {
        FdmRecord expected = new FdmRecord("title".getBytes(StandardCharsets.UTF_8),
                (byte) 0b00001011, 12345, 678,
                List.of("nshortest-1", "nshortest-3"));

        FdmRecord actual = writeThenRead(expected);

        assertArrayEqualsBytes(expected.getField(), actual.getField());
        assertEquals(expected.getType(), actual.getType());
        assertEquals(expected.getFieldLengthSum(), actual.getFieldLengthSum());
        assertEquals(expected.getDocCount(), actual.getDocCount());
        assertIterableEquals(expected.getTokenizers(), actual.getTokenizers());
    }

    @Test
    void roundTripKeepsEmptyTokenizers() throws Exception {
        // 数值字段 / 只存原文的字段记的是空列表 —— 必须能原样读回空，而不是 null
        FdmRecord expected = new FdmRecord("price".getBytes(StandardCharsets.UTF_8),
                (byte) 0b00000111, 100, 100, List.of());

        FdmRecord actual = writeThenRead(expected);

        assertEquals(0, actual.getTokenizers().size());
        assertEquals(100, actual.getFieldLengthSum());
    }

    @Test
    void mergeSumsCountsAndKeepsTokenizers() throws Exception {
        FdmRecord a = new FdmRecord("title".getBytes(StandardCharsets.UTF_8),
                (byte) 0b00001011, 100, 10, List.of("nshortest-1", "nshortest-3"));
        FdmRecord b = new FdmRecord("title".getBytes(StandardCharsets.UTF_8),
                (byte) 0b00001011, 250, 25, List.of("nshortest-1", "nshortest-3"));

        Path fdm = tempDir.resolve("merged.fdm");
        try (FileChannel fc = new RandomAccessFile(fdm.toFile(), "rw").getChannel()) {
            IndexMerger.writeFdmRecord(a, b, fc);
        }
        FdmRecord merged = readBack(fdm);

        assertEquals(350, merged.getFieldLengthSum(), "长度和应当相加");
        assertEquals(35, merged.getDocCount(), "文档数应当相加");
        assertIterableEquals(List.of("nshortest-1", "nshortest-3"), merged.getTokenizers(),
                "tokenizer 列表必须原样保留 —— 合并重写 fdm 时最容易漏的就是它");
    }

    @Test
    void mergeRejectsMismatchedTokenizers() throws Exception {
        FdmRecord a = new FdmRecord("title".getBytes(StandardCharsets.UTF_8),
                (byte) 0b00001011, 100, 10, List.of("nshortest-1"));
        FdmRecord b = new FdmRecord("title".getBytes(StandardCharsets.UTF_8),
                (byte) 0b00001011, 250, 25, List.of("nshortest-3"));

        Path fdm = tempDir.resolve("mismatch.fdm");
        try (FileChannel fc = new RandomAccessFile(fdm.toFile(), "rw").getChannel()) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> IndexMerger.writeFdmRecord(a, b, fc));
            // 消息要能指出是哪个字段、两边各是什么 —— 否则排错时只能靠猜
            org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("title"), e.getMessage());
            org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("nshortest-1"), e.getMessage());
            org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("nshortest-3"), e.getMessage());
        }
    }

    // ------------------------------------------------------------------

    private FdmRecord writeThenRead(FdmRecord record) throws Exception {
        Path fdm = tempDir.resolve("one.fdm");
        try (FileChannel fc = new RandomAccessFile(fdm.toFile(), "rw").getChannel()) {
            IndexMerger.writeFdmRecord(record, fc);
        }
        return readBack(fdm);
    }

    private FdmRecord readBack(Path fdm) throws Exception {
        MemorySegment segment = MMap.mmapFile(fdm.toAbsolutePath().toString(), arena);
        return IndexMerger.readFdmRecord(new SegmentCursor(segment));
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }
}
