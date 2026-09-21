package demo;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;

/**
 * 把一个文件映射进内存并读写的最小示例（JDK 22+ 的 FFM 版）。
 *
 * <p>与旧的 MappedByteBuffer 写法相比：
 * <ul>
 *   <li>寻址用 {@code long}，不再受 {@code Integer.MAX_VALUE}（2 GiB）限制；</li>
 *   <li>映射的生命周期由 {@link Arena} 管理，{@code try-with-resources} 退出即解除，
 *       不再需要 {@code sun.misc.Cleaner} 反射那套 hack。</li>
 * </ul>
 */
public class MemRandomAccess {

    public static final byte[] intToByteArray(int value) {
        return new byte[] {
                (byte)(value >>> 24),
                (byte)(value >>> 16),
                (byte)(value >>> 8),
                (byte)value};
    }

    public static void main(String[] args) throws IOException {
        byte[] input = intToByteArray(16);
        byte[] output = new byte[4];

        try (RandomAccessFile raf = new RandomAccessFile(new File("/opt/temp"), "rw");
             FileChannel fileChannel = raf.getChannel();
             Arena arena = Arena.ofShared()) {

            // 映射进内存
            MemorySegment segment = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, 4, arena);

            // 把 16 当成 4 个 byte 写入，再读回来
            segment.asSlice(0, 4).copyFrom(MemorySegment.ofArray(input));
            MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, 0, output, 0, 4);

            for (byte b : output) {
                System.out.format("0x%x", b);
            }
            System.out.println();
            segment.force();
        }
    }
}
