package hawk.vector.stored;

import common.IndexFormatConfig;
import document.Document;
import field.Field;
import field.FieldType;
import util.DataOutput;
import util.WrapInt;
import util.WrapLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 把「存储字段」（文档原文）落盘 —— 结构照搬文本索引的 {@code .fdt/.fdx/.fdm} 三件套。
 * 方案与取舍见 {@code DESIGN.md} §5.6 与 §7。
 *
 * <h2>三个文件，按**访问模式**切</h2>
 * <pre>
 *   stored.fdt   LZ4 压缩块顺序拼接 —— 顺序写、按块随机读
 *   stored.fdx   [块首pk][fdt偏移][解压后大小] 每块一条 —— 小文件，读侧整体 mmap 常驻，定位零 IO
 *   stored.fdm   [字段名][1字节类型位] 每字段一条 —— 与文档无关的类型表
 * </pre>
 *
 * <h2>块内格式</h2>
 * <pre>
 *   [VLong pk][VInt 字段数] 然后逐个字段的 Field#customSerialize() 输出裸拼接
 * </pre>
 * 每个字段的输出自带「名字长度+名字」和「值长度+值」，所以是**自定界**的，块里不需要额外的分隔。
 * 只把**用到的那些字节**一起 LZ4 压（不压未写满的尾巴）—— 于是块尾就是数据尾。
 *
 * <h2>⚠️ 键用 {@code pk} 而不是 docID —— 这是和文本侧唯一的实质差别</h2>
 * 文本索引只有**一个稠密 docID 空间**，所以它的 {@code .fdx} 按 docID 做 floorKey。
 * 向量索引没有这个东西：它是**每字段一个 ordinal 空间**（{@code title/graph.idx} 一套、
 * {@code descript/graph.idx} 另一套，缺某个字段的文档根本不在那张图里），几套 ordinal 之间没有任何关系。
 * {@code pk} 才是唯一跨字段稳定的标识 —— 而且正好是 {@code VectorHit} 给出来的东西。
 *
 * <h2>单篇文档装不进一个块时：**扩容**（每次翻倍）</h2>
 * 一块默认 16 KiB，通常装得下几十篇文档。但**一篇文档自己的存储字段就可能超过它** ——
 * 比如存的是几千字的正文而不只是标题。这时**把这个块翻倍再试**，直到装得下。
 *
 * <p>（对照：文本侧的 {@code DocWriter.flushStored} 在这种情况下的行为是——</p>
 * <p>重插那一次的返回值**被丢掉了**，于是那篇文档的存储字段**静默消失**，
 * 读它时返回 null、看起来像"被删了"。那是数据丢失，而且不报任何错。这里不照抄。）</p>
 *
 * <h2>块大小是**逐块**的，所以 .fdx 要记每个块解压后多大</h2>
 * 扩容之后各块大小不再相同，读端必须知道"这一块解压出来是多少字节"才能精确分配缓冲。
 * 长度记在 {@code .fdx} 里（见 {@link StoredFields}）。
 *
 * <p>顺带的好处：既然到处都要记长度了，就**只压实际用到的字节**、不再压整块含零填充 ——
 * 于是块尾就是数据尾，读端**扫到尾巴就停**，不存在"把零填充误读成一条 pk=0 的记录"那回事。
 */
public final class StoredFieldsWriter implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(StoredFieldsWriter.class);

    /** 块头：VLong 最长 10 字节 + VInt 最长 5 字节。与文本侧"预留 10 字节放 docID+字段数"同理。 */
    private static final int BLOCK_HEADER_RESERVE = 15;

    private final Path indexDir;
    private final IndexFormatConfig config;

    /** 要存的字段名（有序，决定块内字段的排列顺序）。 */
    private final List<String> storedFields;

    /** 攒着：pk → 该文档序列化后的字段（**已压紧，没有 null**）。TreeMap 顺带把 pk 排好序。 */
    private final TreeMap<Long, byte[][]> pending = new TreeMap<>();

    /** 字段名 → 类型字节。第一次遇到时记下；之后再遇到同样的名字**类型必须一致**。 */
    private final Map<String, Byte> typeBytes = new LinkedHashMap<>();

    private boolean written;

    /**
     * @param storedFields 要存哪些字段；**空列表是合法的**（那这份索引就是不带原文的，
     *                     但三个文件仍然会被写出来 —— 读侧靠"文件在不在"判断不了格式，见 {@link #write()}
     */
    public StoredFieldsWriter(Path indexDir, IndexFormatConfig config, List<String> storedFields) {
        this.indexDir = indexDir;
        this.config = config;
        this.storedFields = List.copyOf(storedFields);
    }

    /**
     * 收一篇文档的存储字段。
     *
     * <p>同一个 pk 再来一次会**覆盖**（并记一条 warn）—— 向量侧本来就有"重复主键会互相覆盖"的行为，
     * 这里保持一致，不额外加一层语义。
     */
    public void addDoc(long pk, Document doc) {
        byte[][] slot = new byte[storedFields.size()][];
        int n = 0;
        for (String name : storedFields) {
            Field field = doc.getFieldMap().get(name);
            if (field == null || field.isStored() != Field.Stored.YES) {
                continue;                       // 这篇文档没有这个字段，或它没被标成 stored
            }
            byte type = FieldType.toTypeByte(field);
            Byte seen = typeBytes.putIfAbsent(name, type);
            if (seen != null && seen != type) {
                // 同一个字段名在同一份索引里出现两种类型 —— 读侧只能按一个类型解，必然有一半解错
                throw new IllegalStateException("字段 '" + name + "' 在同一份索引里出现了两种类型位（"
                        + seen + " / " + type + "），无法用同一个类型表描述");
            }
            slot[n++] = field.customSerialize();
        }
        if (pending.put(pk, Arrays.copyOf(slot, n)) != null) {
            log.warn("存储字段：主键 {} 重复，后一条覆盖前一条", pk);
        }
    }

    /**
     * 分块落盘。
     *
     * <p><b>即使一个存储字段都没有，三个文件也会被写出来</b>（空的）。这是刻意的：
     * 读侧判断"这份索引是不是新格式"用的是 {@code meta.json} 里的格式版本，**不是文件在不在**
     * —— 文件在不在只说明"写的时候跑到了这一步"，两件事不该混。
     */
    public void write() throws IOException {
        if (written) {
            throw new IllegalStateException("已经写过了");
        }
        written = true;

        Files.createDirectories(indexDir);
        Path fdtPath = indexDir.resolve(StoredFields.FDT_FILE);
        Path fdxPath = indexDir.resolve(StoredFields.FDX_FILE);

        byte[] buffer = new byte[config.getBlocSize()];
        byte[] compressed = new byte[config.getCompressor().maxCompressedLength(buffer.length)];
        WrapInt bufferPos = new WrapInt(0);
        WrapLong fdtPos = new WrapLong(0);
        WrapLong fdxPos = new WrapLong(0);

        try (FileChannel fdtChannel = new RandomAccessFile(fdtPath.toFile(), "rw").getChannel();
             FileChannel fdxChannel = new RandomAccessFile(fdxPath.toFile(), "rw").getChannel()) {

            long blockStartPk = 0;
            boolean blockStarted = false;
            for (Map.Entry<Long, byte[][]> entry : pending.entrySet()) {
                long pk = entry.getKey();
                byte[][] data = entry.getValue();

                if (!insertBlock(pk, data, buffer, bufferPos)) {
                    if (blockStarted) {
                        // 这一块装不下了 —— 先把它落盘，再拿新块试这一篇
                        flushBlock(fdtChannel, fdxChannel, buffer, compressed,
                                fdtPos, fdxPos, bufferPos, blockStartPk);
                        blockStarted = false;
                    }
                    if (!insertBlock(pk, data, buffer, bufferPos)) {
                        // ★ 空块都装不下 = **这一篇自己**就超了一个块 → 扩容（翻倍）再试。
                        //   不丢文档：文本侧那种"静默丢掉这一篇"在这里不会发生。
                        buffer = growFor(data);
                        compressed = new byte[config.getCompressor().maxCompressedLength(buffer.length)];
                        if (!insertBlock(pk, data, buffer, bufferPos)) {
                            throw new IllegalStateException("扩容后仍然装不下主键 " + pk
                                    + "（需要 " + needOf(data) + " 字节，缓冲已扩到 " + buffer.length + "）");
                        }
                    }
                }
                if (!blockStarted) {
                    blockStartPk = pk;
                    blockStarted = true;
                }
            }
            if (bufferPos.getValue() > 0) {
                flushBlock(fdtChannel, fdxChannel, buffer, compressed,
                        fdtPos, fdxPos, bufferPos, blockStartPk);
            }
            fdxChannel.force(false);
            fdtChannel.force(false);
        }

        writeFdm();
        log.info("存储字段已落盘：{} 个文档，{} 个字段 -> {}",
                pending.size(), storedFields.size(), indexDir.toAbsolutePath());
    }

    /** 攒了多少篇。 */
    public int pendingCount() {
        return pending.size();
    }

    @Override
    public void close() {
        // 没有需要释放的资源：文件通道都是 try-with-resources
    }

    // ------------------------------------------------------------------

    /**
     * 把一个块写进 .fdt 并在 .fdx 记一条。写完清空缓冲。
     *
     * <p><b>只压用到的字节</b>（{@code bufferPos}），不压整块 —— 于是解压出来的长度就是
     * 数据的精确长度，读端扫到尾巴自然停，不存在零填充。
     */
    private void flushBlock(FileChannel fdtChannel, FileChannel fdxChannel,
                            byte[] buffer, byte[] compressed,
                            WrapLong fdtPos, WrapLong fdxPos, WrapInt bufferPos, long blockStartPk) {
        int used = bufferPos.getValue();
        // .fdx：块首 pk → 本块在 .fdt 的起始偏移 + **解压后大小**（先记，再写块 —— 写块会推进 fdtPos）
        DataOutput.writeVLong(blockStartPk, fdxChannel, fdxPos);
        DataOutput.writeVLong(fdtPos, fdxChannel, fdxPos);
        DataOutput.writeVInt(used, fdxChannel, fdxPos);

        int compressedLength = config.getCompressor()
                .compress(buffer, 0, used, compressed, 0, compressed.length);
        DataOutput.writeBytes(ByteBuffer.wrap(compressed, 0, compressedLength), fdtChannel, fdtPos);

        Arrays.fill(buffer, 0, used, (byte) 0);     // 只清用过的部分，扩容过的尾巴不用碰
        bufferPos.setValue(0);
    }

    /** 一篇文档序列化后需要多少字节（含块头预留）。 */
    private static int needOf(byte[][] data) {
        int need = BLOCK_HEADER_RESERVE;
        for (byte[] d : data) {
            need += d.length;
        }
        return need;
    }

    /** 按需扩容：每次翻倍，直到装得下这一篇。 */
    private byte[] growFor(byte[][] data) {
        int need = needOf(data);
        long newSize = config.getBlocSize();
        while (newSize < need) {
            newSize <<= 1;                          // 翻倍
        }
        log.warn("单篇文档的存储字段需要 {} 字节，超过块大小 {} —— 本块扩容到 {} 字节",
                need, config.getBlocSize(), newSize);
        return new byte[(int) newSize];
    }

    /**
     * 试着把一篇文档放进块里。
     *
     * @return 放得下就写进去并返回 true；放不下原样返回 false（缓冲不动）
     */
    private boolean insertBlock(long pk, byte[][] data, byte[] buffer, WrapInt pos) {
        if (needOf(data) > buffer.length - pos.getValue()) {
            return false;
        }
        DataOutput.writeVLong(pk, buffer, pos);
        DataOutput.writeVInt(data.length, buffer, pos);
        for (byte[] d : data) {
            DataOutput.writeBytes(d, buffer, pos);
        }
        return true;
    }

    /** {@code [Int 名长][字段名][1字节类型位]}，按字段名排序 —— 与文本侧同格式，只是少了两个 BM25 统计量。 */
    private void writeFdm() throws IOException {
        List<String> sorted = new ArrayList<>(typeBytes.keySet());
        Collections.sort(sorted);

        Path fdmPath = indexDir.resolve(StoredFields.FDM_FILE);
        try (FileChannel fc = new RandomAccessFile(fdmPath.toFile(), "rw").getChannel()) {
            WrapLong pos = new WrapLong(0);
            for (String name : sorted) {
                byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
                DataOutput.writeInt(bytes.length, fc, pos);
                DataOutput.writeBytes(bytes, fc, pos);
                DataOutput.writeByte(typeBytes.get(name), fc, pos);
            }
            fc.force(false);
        }
    }
}
