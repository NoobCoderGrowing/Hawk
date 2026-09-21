package directory.memory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;

public class MMap {

    // JDK 22+ 的 java.lang.foreign 以 long 寻址，单次 FileChannel.map 不再受
    // Integer.MAX_VALUE（2 GiB）限制，因此单文件可以超过 2 GiB。
    //
    // 映射的生命周期交给传入的 Arena：Arena.close() 即解除映射。
    // 跨线程共享的 reader 必须使用 Arena.ofShared()，否则访问会抛异常；
    // 单线程的短生命周期用途（如 IndexMerger）可以用 Arena.ofConfined()。

    public static MemorySegment mmapFile(String file, Arena arena) throws IOException {
        try (FileChannel fc = new RandomAccessFile(file, "r").getChannel()) {
            return fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size(), arena);
        }
    }

    public static MemorySegment[] mmapFiles(String[] files, Arena arena) throws IOException {
        MemorySegment[] segments = new MemorySegment[files.length];
        for (int i = 0; i < files.length; i++) {
            try (FileChannel fc = new RandomAccessFile(files[i], "rw").getChannel()) {
                segments[i] = fc.map(FileChannel.MapMode.READ_WRITE, 0, fc.size(), arena);
            }
        }
        return segments;
    }
}
