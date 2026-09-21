package directory.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import util.DataInput;
import util.DataOutput;
import util.WrapLong;

import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证基于 MemorySegment 的寻址突破了旧实现的 2 GiB 上限。
 *
 * <p>旧实现把 64 位偏移窄化成 {@code int} 再交给 {@code ByteBuffer.position(int)} /
 * {@code get(int)}，二者都要求非负，因此 offset 一旦超过 {@link Integer#MAX_VALUE}
 * （2 GiB - 1）就会变负并抛 {@code IndexOutOfBoundsException}。
 * 本测试把数据写在 2 GiB 之后，确认能原样读回。
 *
 * <p>用稀疏文件，不实际占用磁盘。
 */
class LargeOffsetTest {

    /** 2 GiB + 64 KiB，位于旧实现的硬边界之外 */
    private static final long BEYOND_2GIB = (1L << 31) + 65536L;

    @TempDir
    Path tempDir;

    @Test
    void readsBackValuesWrittenBeyondTwoGiB() throws Exception {
        Path file = tempDir.resolve("large.bin");
        int vintValue = 300;                // 两字节编码，验证多字节读取
        long vlongValue = 5_000_000_000L;   // 超出 int 范围，旧实现必丢
        byte[] blob = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};

        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw");
             FileChannel fc = raf.getChannel();
             Arena arena = Arena.ofShared()) {

            raf.setLength(BEYOND_2GIB + 256); // 稀疏文件：只设长度，不占盘

            WrapLong writePos = new WrapLong(BEYOND_2GIB);
            DataOutput.writeVInt(vintValue, fc, writePos);
            DataOutput.writeVLong(vlongValue, fc, writePos);
            DataOutput.writeBytes(blob, fc, writePos);
            fc.force(true);

            MemorySegment seg = MMap.mmapFile(file.toString(), arena);
            assertTrue(seg.byteSize() > Integer.MAX_VALUE,
                    "映射必须超过 2 GiB 才有意义，实际 " + seg.byteSize());

            // 2 GiB 之后读写往返
            WrapLong readPos = new WrapLong(BEYOND_2GIB);
            assertEquals(vintValue, DataInput.readVintAt(seg, readPos));
            assertEquals(vlongValue, DataInput.readVlongAt(seg, readPos));
            assertArrayEquals(blob, DataInput.readBytes(seg, readPos.getValue(), blob.length));

            // 绝对定位读取（FST 偏移走的正是这条路径）
            assertEquals(vintValue, DataInput.readVintAt(seg, BEYOND_2GIB));

            // 说明本测试为何有意义：旧实现正是这么窄化的，结果必然为负
            assertTrue((int) BEYOND_2GIB < 0,
                    "旧实现 (int) 窄化后偏移变负，这就是原 2 GiB 上限的成因");
        }
    }
}
