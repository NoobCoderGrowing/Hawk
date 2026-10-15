package hawk.vector.stored;

/**
 * 存储字段那三个文件的名字与**格式版本** —— 写入器与读取器共用，别在两处各写一份字符串常量。
 *
 * <h2>格式版本为什么必须有</h2>
 * 存储字段是**后加**的能力：这次改动之前建的向量索引**没有**那三个文件。
 * 读侧必须能分清两件事：
 * <ul>
 *   <li><b>老格式</b>（没有存储字段这一层）—— 该拒绝，要求重建；</li>
 *   <li><b>新格式但一个字段都没存</b>（比如请求里所有字段都是 {@code isStored=NO}）—— 合法，正常服务。</li>
 * </ul>
 * 光看"文件在不在"是分不清的（第一种文件不在，第二种文件在但是空的 —— 可一旦哪天有人手删了文件，
 * 又变成第一种的样子）。所以用 {@link #FORMAT_VERSION} 显式记在 {@code meta.json} 里。
 *
 * <p><b>版本对不上就拒绝打开</b>，与文本索引对 BKD 格式的做法一致（{@code MMapDirectoryReader.loadBkd}
 * 要求 {@code formatVersion >= CURRENT}）—— 宁可要求重建，也不留"半吊子状态"：
 * 否则同一个查询打到不同版本的分片上，一个有 {@code fields} 一个没有，而两边看起来都正常。
 */
public final class StoredFields {

    /** LZ4 压缩块，顺序拼接。 */
    public static final String FDT_FILE = "stored.fdt";

    /**
     * {@code [VLong 块首pk][VLong fdt偏移][VInt 解压后大小]}，每块一条。小文件，读侧整体 mmap。
     *
     * <p>第三个字段（解压后大小）是必须的：块**逐块变长** —— 写入端在"单篇文档装不进一个块"时会把
     * 那个块翻倍，所以不能像文本侧那样"固定按 blocSize 解"。LZ4 的快速解压器不读长度头，
     * 长度只能由我们自己记。
     */
    public static final String FDX_FILE = "stored.fdx";

    /** {@code [Int 名长][字段名][1字节类型位]}，每字段一条，按字段名排序。 */
    public static final String FDM_FILE = "stored.fdm";

    /** 存储字段的格式版本。写进 {@code meta.json}；读侧要求 {@code >=} 它。 */
    public static final int FORMAT_VERSION = 1;

    private StoredFields() {
    }
}
