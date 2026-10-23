package directory;

/**
 * 索引的**磁盘格式版本** —— 写进 {@code segment.info} 偏移 16，读侧按它拒绝旧索引。
 *
 * <h2>一个号，不是两个</h2>
 * {@code segment.info} 里只有这一个版本号（见 {@link SegmentInfo}），它描述的是**整个索引**的格式。
 * 本类原名 {@code util.bkd.BkdFormatVersion} —— 那时推着它走的只有 BKD 的编码变更；
 * 但 v3（fdm 每字段记录 tokenizer 名）是非 BKD 的格式变更也推它，那个名字就名不副实了，
 * 于是搬到 {@code directory} 包与 {@link SegmentInfo} 同处。
 *
 * <p><b>别与 {@link util.bkd.BkdFormat#FORMAT_VERSION} 混淆</b>：那个是 {@code .bkd}
 * **文件自己的文件头**版本，另一套常量，不受这里影响。
 *
 * <h2>版本号的意义：落盘字节变了，旧文件解出来就是错的</h2>
 * 所以读方要**按版本号拒绝**，而不是静默给出错误结果 —— 守卫在 {@link SegmentInfo} 的读路径上，
 * 一处覆盖"打开检索 / 追加 / 段合并"三条路。
 */
public final class IndexFormatVersion {

    /** 最老的数值索引：多级前缀 term 走倒排（{@code util.NumericTrie}），不走 BKD。 */
    public static final int LEGACY_PREFIX = 0;

    /** 第一版 BKD。数值统一按 double 编码存 sortable long。 */
    public static final int BKD = 1;

    /**
     * 第二版 BKD，修了 {@code util.NumberUtil#double2SortableLong} 的**跨零不单调**问题。
     *
     * <p>旧实现对负数是 {@code ~bits}（翻全部 64 位），把负数翻成了正数，
     * 于是所有负数排在正数之后，跨零的区间查询全错。
     *
     * <p>编码变了 → 旧索引里的 long 解出来是错的 → **必须重建**。
     * 同时这一版开始支持 {@code util.NumberUtil#long2SortableLong}（long 字段），
     * 与 double 编码区分靠字段类型位，不靠版本号。
     */
    public static final int BKD_V2 = 2;

    /**
     * 第三版：{@code N.fdm} 每条字段记录**末尾追加**一组 tokenizer 名字（见 {@code DESIGN.md} §6.7）。
     *
     * <p>加它的原因：召回时要用**建索引时那组** tokenizer 分词，否则切出来的词在倒排里不存在 ——
     * 不报错，只是静默搜不到。名字落盘后读端就能照用，也能对不上时报出来。
     *
     * <p>记录变长了 → 旧的读法会把后面的字节当成下一条记录 → **必须重建**，不做兼容分支。
     * 与 v2 一样，宁可要求重建，也不留"半张索引有新字段、半张没有"的半吊子状态。
     */
    public static final int TOKENIZER_META = 3;

    /** 当前写入的版本。 */
    public static final int CURRENT = TOKENIZER_META;

    private IndexFormatVersion() {
    }
}
