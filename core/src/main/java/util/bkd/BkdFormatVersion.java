package util.bkd;

/**
 * BKD 索引文件的格式版本。
 *
 * <p>版本号的意义：**落盘的 sortable long 编码变了，旧文件解出来就是错的**，
 * 所以读取方要按版本号拒绝，而不是静默给出错误结果。
 */
public final class BkdFormatVersion {

    /** 最老的数值索引：多级前缀 term 走倒排（{@link util.NumericTrie}），不走 BKD。 */
    public static final int LEGACY_PREFIX = 0;

    /** 第一版 BKD。数值统一按 double 编码存 sortable long。 */
    public static final int BKD = 1;

    /**
     * 第二版 BKD，修了 {@link util.NumberUtil#double2SortableLong} 的**跨零不单调**问题。
     *
     * <p>旧实现对负数是 {@code ~bits}（翻全部 64 位），把负数翻成了正数，
     * 于是所有负数排在正数之后，跨零的区间查询全错。
     *
     * <p>编码变了 → 旧索引里的 long 解出来是错的 → **必须重建**。
     * 同时这一版开始支持 {@link util.NumberUtil#long2SortableLong}（long 字段），
     * 与 double 编码区分靠字段类型位，不靠版本号。
     */
    public static final int BKD_V2 = 2;

    /** 当前写入的版本。 */
    public static final int CURRENT = BKD_V2;

    private BkdFormatVersion() {
    }
}
