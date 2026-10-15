package hawk.vector.stored;

import common.IndexFormatConfig;
import directory.memory.MMap;
import document.Document;
import field.FieldType;
import util.DataInput;
import util.WrapLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 读存储字段（文档原文）—— {@link StoredFieldsWriter} 的读侧，结构照搬文本索引的
 * {@code MMapDirectoryReader} + {@code Searcher.doc}。
 *
 * <h2>一次读取怎么走</h2>
 * <pre>
 *   pk →(.fdx floorEntry)→ 块首 pk + 该块在 .fdt 的偏移 + **该块解压后多大**
 *      → 相邻那条 .fdx 记录给出块的右边界（没有下一条就到文件尾）
 *      → 读出这段压好的字节，按记下来的长度精确分配、LZ4 解出来
 *      → **块内线性扫**，读到 pk 相等的那条，把字段解出来
 * </pre>
 * 是"块级随机 + 块内线性"：{@code .fdx} 每 16 KiB 才一条，索引体积比"每文档一条"小几个数量级；
 * 代价是块内最多扫几十条文档 —— 用固定小块把这成本封顶。
 *
 * <h2>块大小是逐块的，所以长度必须记在 .fdx 里</h2>
 * 写入端在"单篇文档装不进一个块"时会**把这个块翻倍**（见 {@link StoredFieldsWriter}），
 * 于是各块大小不再相同。LZ4 的快速解压器要求**事先知道解压后多大**（它不读长度头），
 * 所以这个数只能记在 {@code .fdx} 里。
 *
 * <p>顺带的好处：写入端因此**只压实际用到的字节**，块尾就是数据尾 ——
 * 读端扫到 {@code buffer.limit()} 自然停，**不存在零填充**，
 * 也就没有"把填充误读成一条 pk=0 的记录"那回事（本来要为它单独论证一番）。
 *
 * <h2>⚠️ 打不开就抛，不 {@code System.exit}</h2>
 * 文本侧的 {@code MMapDirectoryReader} 在文件缺失时是 {@code System.exit(1)} ——
 * 对一次性进程没问题，但云上的召回节点是**常驻服务**，一个分片的文件缺失不该把整台机器带走。
 * 这里抛 {@link IOException}，由调用方转成 503（让协调器换个副本重试）。
 */
public final class StoredFieldsReader implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(StoredFieldsReader.class);

    private static final ValueLayout.OfInt INT_BE =
            ValueLayout.JAVA_INT.withOrder(ByteOrder.BIG_ENDIAN).withByteAlignment(1);

    /** 一条 .fdx 记录：本块在 .fdt 的偏移（VLong 的原始字节，用时再解）+ 解压后多大。 */
    private record FdxEntry(byte[] offsetBytes, int uncompressedSize) {
    }

    private final Arena arena;
    private final MemorySegment fdt;
    private final TreeMap<Long, FdxEntry> fdx;
    /** 字段名 → 类型字节。 */
    private final Map<String, Byte> fdm;
    private final IndexFormatConfig config;

    private StoredFieldsReader(Arena arena, MemorySegment fdt, TreeMap<Long, FdxEntry> fdx,
                               Map<String, Byte> fdm, IndexFormatConfig config) {
        this.arena = arena;
        this.fdt = fdt;
        this.fdx = fdx;
        this.fdm = fdm;
        this.config = config;
    }

    /**
     * 打开一份索引的存储字段。
     *
     * @throws IOException 三个文件缺任何一个 —— 大概率是**旧格式**建的索引（没有存储字段这一层），
     *                     需要重建。调用方应当把这个版本判成"服务不了"（回 503），
     *                     而不是当"这篇文档没有原文"糊过去
     */
    public static StoredFieldsReader open(Path indexDir, IndexFormatConfig config) throws IOException {
        Path fdtPath = indexDir.resolve(StoredFields.FDT_FILE);
        Path fdxPath = indexDir.resolve(StoredFields.FDX_FILE);
        Path fdmPath = indexDir.resolve(StoredFields.FDM_FILE);
        for (Path p : new Path[]{fdtPath, fdxPath, fdmPath}) {
            if (!Files.isRegularFile(p)) {
                throw new IOException("存储字段文件缺失：" + p.getFileName()
                        + "（这份索引是旧格式建的，或包不完整 —— 需要重建索引）");
            }
        }

        Arena arena = Arena.ofShared();
        try {
            MemorySegment fdt = MMap.mmapFile(fdtPath.toAbsolutePath().toString(), arena);
            fdt.load();                              // 顺序扫块，预载划算
            return new StoredFieldsReader(arena, fdt, loadFdx(fdxPath, arena), loadFdm(fdmPath, arena), config);
        } catch (IOException | RuntimeException e) {
            arena.close();                           // 半开着别漏（映射由 arena 统一解除）
            throw e;
        }
    }

    /**
     * 按主键取原文。
     *
     * @return 该文档的字段；**这个 pk 不在这份索引里** → {@code null}。
     *         一篇"在索引里、但没有存储字段"的文档返回的是**空 Document**（不是 null）——
     *         这两种情况调用方得分得清
     */
    public Document docOf(long pk) {
        Map.Entry<Long, FdxEntry> left = fdx.floorEntry(pk);
        if (left == null) {
            return null;                             // 比第一块的起始 pk 还小 —— 不在索引里
        }
        Map.Entry<Long, FdxEntry> right = fdx.higherEntry(left.getKey());
        long offsetLeft = DataInput.readVlong(left.getValue().offsetBytes());
        long offsetRight = right == null ? fdt.byteSize() : DataInput.readVlong(right.getValue().offsetBytes());

        int blockLength = (int) (offsetRight - offsetLeft);
        byte[] compressedBlock = DataInput.readBytes(fdt, offsetLeft, blockLength);
        // 按 .fdx 记下来的长度**精确**分配 —— 块是逐块变长的（写入端会为超大文档翻倍）
        byte[] block = new byte[left.getValue().uncompressedSize()];
        config.getDecompressor().decompress(compressedBlock, block);

        return scanBlock(block, pk);
    }

    /** 这份索引存了哪些字段（见 {@code .fdm}）。 */
    public Set<String> storedFieldNames() {
        return java.util.Collections.unmodifiableSet(fdm.keySet());
    }

    @Override
    public void close() {
        arena.close();                               // 一次解除全部映射（.fdt/.fdx/.fdm）
    }

    // ------------------------------------------------------------------

    /**
     * 块内线性扫，找到 pk 相等的那条就把它整条的字段解出来。
     *
     * <p>扫到 {@code buffer.limit()} 就停 —— 而块尾**就是**数据尾（写入端只压用到的字节），
     * 所以这里不会读到任何边角料。
     */
    private Document scanBlock(byte[] block, long targetPk) {
        ByteBuffer buffer = ByteBuffer.wrap(block);
        while (buffer.position() < buffer.limit()) {
            long curPk = DataInput.readVlong(buffer);
            int fieldCount = DataInput.readVint(buffer);
            boolean hit = curPk == targetPk;
            Document result = hit ? new Document() : null;

            for (int i = 0; i < fieldCount; i++) {
                int nameLength = DataInput.readVint(buffer);
                byte[] nameBytes = new byte[nameLength];
                buffer.get(nameBytes);
                int valueLength = DataInput.readVint(buffer);
                byte[] value = new byte[valueLength];
                buffer.get(value);

                if (hit) {
                    String name = new String(nameBytes, StandardCharsets.UTF_8);
                    Byte typeByte = fdm.get(name);
                    if (typeByte == null) {
                        // 类型表里没有这个名字 —— .fdm 和数据块对不上，包被改坏了
                        throw new IllegalStateException(
                                "字段 '" + name + "' 不在类型表里（.fdm 与数据块不一致，索引可能被改坏了）");
                    }
                    result.add(FieldType.decodeStored(typeByte, name, value));
                }
            }
            if (hit) {
                return result;
            }
        }
        return null;                                 // 这一块里没有（理论上不该：floorEntry 已保证起点 ≤ pk）
    }

    /** {@code .fdx}：{@code [VLong 块首pk][VLong fdt偏移][VInt 解压后大小]} —— 小文件，整体读进有序表。 */
    private static TreeMap<Long, FdxEntry> loadFdx(Path path, Arena arena) throws IOException {
        MemorySegment seg = MMap.mmapFile(path.toAbsolutePath().toString(), arena);
        TreeMap<Long, FdxEntry> out = new TreeMap<>();
        WrapLong pos = new WrapLong(0);
        long size = seg.byteSize();
        while (pos.getValue() < size) {
            long blockStartPk = DataInput.readVlongAt(seg, pos);
            byte[] offsetBytes = DataInput.readVlongBytesAt(seg, pos);   // 偏移留到用时再解
            int uncompressedSize = DataInput.readVintAt(seg, pos);
            out.put(blockStartPk, new FdxEntry(offsetBytes, uncompressedSize));
        }
        return out;
    }

    /**
     * {@code .fdm}：{@code [Int 名长][字段名][1字节类型位]}，按字段名排序。
     *
     * <p>比文本侧少两个字段（{@code fieldLengthSum} / {@code docCount}）—— 那是给 BM25 用的统计量，
     * 向量侧不需要语料统计量。**类型位是同一个约定**，由 {@code FieldType} 统一解释。
     */
    private static Map<String, Byte> loadFdm(Path path, Arena arena) throws IOException {
        MemorySegment seg = MMap.mmapFile(path.toAbsolutePath().toString(), arena);
        Map<String, Byte> out = new HashMap<>();
        long size = seg.byteSize();
        long pos = 0;
        while (pos < size) {
            int nameLength = seg.get(INT_BE, pos);
            pos += 4;
            byte[] nameBytes = DataInput.readBytes(seg, pos, nameLength);
            pos += nameLength;
            byte typeByte = seg.get(ValueLayout.JAVA_BYTE, pos);
            pos += 1;
            out.put(new String(nameBytes, StandardCharsets.UTF_8), typeByte);
        }
        return out;
    }
}
