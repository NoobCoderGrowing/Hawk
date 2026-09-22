package hawk.vector.index;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读 NumPy 的 .npy 文件。
 *
 * <p>支持两类：
 * <ul>
 *   <li>{@link #readFloatMatrix} —— 二维浮点矩阵（<b>商品向量</b>），dtype {@code <f2} / {@code <f4}</li>
 *   <li>{@link #readLongArray} —— 一维整数数组（<b>行号 → 商品 ID 的映射</b>），dtype {@code <i8} / {@code <i4}</li>
 * </ul>
 *
 * <p>为什么 Java 侧要读 .npy：语料向量与 ID 映射目前由 Python 编码产出，
 * 直接读能省掉一次格式转换和中间产物。
 *
 * <p>格式（v1.0 / v2.0）：
 * <pre>
 *   6 字节  magic "\x93NUMPY"
 *   2 字节  版本（major, minor）
 *   2 或 4 字节  header 长度（小端）
 *   n 字节  header：一个 Python dict 字面量，如
 *           {'descr': '&lt;f2', 'fortran_order': False, 'shape': (1002494, 512), }
 *   ...     数据（64 字节对齐后开始）
 * </pre>
 */
public final class NpyReader {

    private static final byte[] MAGIC = {(byte) 0x93, 'N', 'U', 'M', 'P', 'Y'};
    private static final Pattern DESCR = Pattern.compile("'descr'\\s*:\\s*'([^']+)'");
    private static final Pattern SHAPE = Pattern.compile("'shape'\\s*:\\s*\\(([^)]*)\\)");

    private NpyReader() {
    }

    /** .npy 头部解析结果。 */
    private record Header(String descr, int fileRows, int cols, long dataStart, long dataBytes) {
        /** 只读前 limit 行时，实际要读的字节数。 */
        long bytesFor(int rows) {
            return (long) rows * cols * itemBytes();
        }

        int itemBytes() {
            return switch (descr) {
                case "<f4", "|f4", "<i4", "|i4" -> 4;
                case "<f2", "|f2" -> 2;
                case "<i8", "|i8" -> 8;
                default -> -1;
            };
        }
    }

    // ------------------------------------------------------------------ 浮点矩阵

    /** 读成 (N, D) 的 float 矩阵，读全部行。 */
    public static float[][] readFloatMatrix(Path path) throws IOException {
        return readFloatMatrix(path, Integer.MAX_VALUE);
    }

    /**
     * 读成 (rows, D) 的 float 矩阵，最多读 {@code limit} 行。支持 float16(&lt;f2) 与 float32(&lt;f4)。
     *
     * <p><b>limit 必须在读取阶段生效</b>，而不是读完再截断 ——
     * 语料向量 100 万 × 512 在 fp32 下是 2 GB，如果只想要前 3 万条却先把 2 GB
     * 读进堆，堆就直接爆了。所以 limit 必须在读取阶段生效，而不是读完再截断。
     *
     * <p>用 mmap 而不是整文件读进堆，避免字节数组和 float 矩阵同时在堆里。
     */
    public static float[][] readFloatMatrix(Path path, int limit) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
             FileChannel channel = raf.getChannel()) {

            Header h = parseHeader(channel, path);
            if (h.itemBytes() != 2 && h.itemBytes() != 4) {
                throw new IOException("readFloatMatrix 不支持的 dtype: " + h.descr()
                        + "（只支持 <f2 / <f4；整数数组请用 readLongArray）");
            }
            int rows = Math.min(h.fileRows(), Math.max(0, limit));
            requireHeap("读入 float32 矩阵", outputHeapBytes(rows, h.cols()));

            MappedByteBuffer data = channel.map(
                    FileChannel.MapMode.READ_ONLY, h.dataStart(), h.bytesFor(rows));
            data.order(ByteOrder.LITTLE_ENDIAN);   // 绝对位置 get 会遵循这个字节序
            float[][] out = new float[rows][h.cols()];

            // 用 ByteBuffer 的绝对位置读取，而不是 asFloatBuffer()/asShortBuffer() 视图 ——
            // 视图缓冲区的字节序是否继承父缓冲区在规范里不明确，显式走父缓冲区没有歧义。
            // HotSpot 会把 getFloat(int)/getShort(int) 内联成单条加载指令，够快。
            if (h.itemBytes() == 4) {
                for (int r = 0; r < rows; r++) {
                    int base = r * h.cols() * 4;
                    float[] row = out[r];
                    for (int c = 0; c < h.cols(); c++) {
                        row[c] = data.getFloat(base + c * 4);
                    }
                }
            } else {
                for (int r = 0; r < rows; r++) {
                    int base = r * h.cols() * 2;
                    float[] row = out[r];
                    for (int c = 0; c < h.cols(); c++) {
                        row[c] = Float.float16ToFloat(data.getShort(base + c * 2));
                    }
                }
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ 整数数组

    /** 读一维整数数组（如行号 → 商品 ID 的映射）。支持 int64(&lt;i8) 与 int32(&lt;i4)。 */
    public static long[] readLongArray(Path path) throws IOException {
        return readLongArray(path, Integer.MAX_VALUE);
    }

    /** 读一维整数数组，最多读 {@code limit} 个元素。 */
    public static long[] readLongArray(Path path, int limit) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
             FileChannel channel = raf.getChannel()) {

            Header h = parseHeader(channel, path);
            if (h.cols() != 1) {
                throw new IOException("readLongArray 只支持一维数组，实际 shape 列数 = " + h.cols());
            }
            int itemBytes = h.itemBytes();
            if (itemBytes != 8 && itemBytes != 4) {
                throw new IOException("readLongArray 不支持的 dtype: " + h.descr()
                        + "（只支持 <i8 / <i4）");
            }
            int n = Math.min(h.fileRows(), Math.max(0, limit));
            requireHeap("读入 long 数组", (long) n * 8 + 64);

            MappedByteBuffer data = channel.map(
                    FileChannel.MapMode.READ_ONLY, h.dataStart(), h.bytesFor(n));
            data.order(ByteOrder.LITTLE_ENDIAN);
            long[] out = new long[n];
            if (itemBytes == 8) {
                for (int i = 0; i < n; i++) {
                    out[i] = data.getLong(i * 8);
                }
            } else {
                for (int i = 0; i < n; i++) {
                    out[i] = data.getInt(i * 4);
                }
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ 预检

    /** 读成 float[][] 后占用的堆（**与文件 dtype 无关** —— 输出永远是 fp32）。 */
    private static long outputHeapBytes(int rows, int cols) {
        // 主体 + 每个 float[] 的对象头（行数多时不容忽略：100 万行约 16 MB）
        return (long) rows * cols * 4L + (long) rows * 16L;
    }

    /**
     * 堆够不够。
     *
     * <p><b>为什么要有这一步</b>：栈索引里的 {@code corpus_emb.npy} 是 100 万 × 512，
     * fp16 文件 1 GB，但读进堆要转成 fp32 的 **2 GB** —— 而 JVM 默认堆上限只有
     * 物理内存的 1/4（本机约 2 GB）。不加 limit 的话第一行就 OOM，
     * 堆栈指向 {@code new float[rows][cols]}，完全看不出该怎么办。
     *
     * <p>这里提前拦下来，把 OOM 换成一句能操作的话（要么给 limit，要么加 -Xmx）。
     */
    private static void requireHeap(String what, long bytes) {
        long maxHeap = Runtime.getRuntime().maxMemory();
        if (bytes * 4 / 3 < maxHeap) {   // 留 1/3 余量给后续处理
            return;
        }
        throw new OutOfMemoryError(String.format(
                "%s 需要约 %.2f GB 堆，而当前堆上限只有 %.2f GB。%n"
                        + "  · 只读前 N 行：  readFloatMatrix(path, N)%n"
                        + "  · 或加大堆：     -Xmx%dg%n"
                        + "  · 或走 CLI：     --limit N 只读前 N 条",
                what, bytes / 1e9, maxHeap / 1e9, (long) (bytes / 1e9 * 2) + 1));
    }

    // ------------------------------------------------------------------ 头部解析

    private static Header parseHeader(FileChannel channel, Path path) throws IOException {
        long fileSize = channel.size();
        ByteBuffer head = ByteBuffer.allocate((int) Math.min(512, fileSize))
                .order(ByteOrder.LITTLE_ENDIAN);
        channel.read(head, 0);
        head.flip();

        byte[] magic = new byte[6];
        head.get(magic);
        for (int i = 0; i < MAGIC.length; i++) {
            if (magic[i] != MAGIC[i]) {
                throw new IOException("不是 .npy 文件（magic 不匹配）: " + path);
            }
        }
        int major = head.get() & 0xFF;
        head.get(); // minor
        int headerLen = (major == 1) ? (head.getShort() & 0xFFFF) : head.getInt();
        int headerStart = (major == 1) ? 10 : 12;

        ByteBuffer headerBuf = ByteBuffer.allocate(headerLen);
        channel.read(headerBuf, headerStart);
        String header = new String(headerBuf.array(), StandardCharsets.UTF_8);

        Matcher dm = DESCR.matcher(header);
        Matcher sm = SHAPE.matcher(header);
        if (!dm.find() || !sm.find()) {
            throw new IOException("无法解析 .npy 头部: " + header);
        }
        String descr = dm.group(1);
        // shape 形如 "(1002494, 512)" 或一维的 "(1002494,)"
        String[] dims = sm.group(1).split(",");
        int fileRows = Integer.parseInt(dims[0].trim());
        int cols = dims.length > 1 ? Integer.parseInt(dims[1].trim()) : 1;

        Header h = new Header(descr, fileRows, cols, headerStart + headerLen, 0);
        int itemBytes = h.itemBytes();
        if (itemBytes < 0) {
            throw new IOException("不支持的 dtype: " + descr
                    + "（浮点用 <f2/<f4，整数用 <i8/<i4）");
        }
        long fileBytes = (long) fileRows * cols * itemBytes;
        if (fileSize - h.dataStart() < fileBytes) {
            throw new IOException("文件长度不足：需要 " + fileBytes
                    + " 字节数据，实际只有 " + (fileSize - h.dataStart()));
        }
        return h;
    }
}
