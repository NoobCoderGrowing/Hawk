package hawk.recall.query;

import java.util.Objects;

/**
 * 数值区间查询：某个数值字段落在 {@code [lower, upper]} 内（端点开闭可指定）。
 *
 * <p>取代原来的 {@code NumericRangeQuery}。两点改动：
 * <ul>
 *   <li><b>用 {@code double} 而不是 {@code Number}</b> —— 原构造器签名是
 *       {@code (String, Number, Number)}，内部却写 {@code (double) lower}，
 *       传 {@code Integer} 会在运行时炸 {@code ClassCastException}。
 *       签名说一套、实现做一套，是用户输入路径上的地雷。</li>
 *   <li><b>端点开闭显式化</b> —— 可以在 BKD 的 sortable long 上精确实现：
 *       该编码是**单调**的，开区间只需在 sortable 域上 ±1（等价于取下一个可表示的 double）。</li>
 * </ul>
 *
 * <p>数值区间天然是"筛选"语义（分数恒为 0），放进 {@link Occur#FILTER} 最贴切。
 */
public record RangeQuery(String field, double lower, double upper,
                         boolean includeLower, boolean includeUpper) implements Query {

    public RangeQuery {
        Objects.requireNonNull(field, "field 不能为空");
        if (Double.isNaN(lower) || Double.isNaN(upper)) {
            throw new IllegalArgumentException("区间端点不能是 NaN");
        }
        if (lower > upper) {
            throw new IllegalArgumentException(
                    "下界大于上界：" + lower + " > " + upper);
        }
        if (lower == upper && !(includeLower && includeUpper)) {
            // 闭退化成开就成了空集，与其静默返回 0 条，不如让调用方自己决定要不要这么写
            throw new IllegalArgumentException(
                    "上下界相等但区间是开的，这永远是空集：" + lower);
        }
    }

    /** {@code [lower, upper]} 双闭。 */
    public static RangeQuery closed(String field, double lower, double upper) {
        return new RangeQuery(field, lower, upper, true, true);
    }

    /** {@code (lower, upper)} 双开。 */
    public static RangeQuery open(String field, double lower, double upper) {
        return new RangeQuery(field, lower, upper, false, false);
    }

    /** 单侧无界：{@code (-∞, upper]}。 */
    public static RangeQuery atMost(String field, double upper) {
        return new RangeQuery(field, Double.NEGATIVE_INFINITY, upper, false, true);
    }

    /** 单侧无界：{@code [lower, +∞)}。 */
    public static RangeQuery atLeast(String field, double lower) {
        return new RangeQuery(field, lower, Double.POSITIVE_INFINITY, true, false);
    }
}
