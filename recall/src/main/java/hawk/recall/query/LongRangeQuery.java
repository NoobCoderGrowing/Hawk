package hawk.recall.query;

import java.util.Objects;

/**
 * 长整数区间查询 —— 查的是用 {@code LongField} 索引的字段。
 *
 * <h2>与 {@link RangeQuery} 的关系</h2>
 * 两者**不能混用**：
 * <ul>
 *   <li>{@link RangeQuery} 查 {@code NumberField}（double）索引的字段，编码走后者的 double→sortable</li>
 *   <li>本类查 {@code LongField}（long）索引的字段，编码走 long→sortable（只翻符号位）</li>
 * </ul>
 * 拿本类去查 double 字段，比较的是两套不同编码的 long，结果是垃圾。
 * 所以字段类型在 schema 里被区分开，编译期就会拦下来。
 *
 * <h2>为什么需要它</h2>
 * double 只有 52 位尾数，**超过 2^53 的整数存不准**。纳秒时间戳、雪花 ID、
 * 大额精确整数这类值必须走 long 字段，本类才是有意义的。
 *
 * <p>区间开闭在 sortable long 上实现：long 的编码是**稠密**的，
 * 开区间只需 ±1，精确无误（不像 double 要考虑"下一个可表示的浮点数"）。
 */
public record LongRangeQuery(String field, long lower, long upper,
                             boolean includeLower, boolean includeUpper) implements Query {

    public LongRangeQuery {
        Objects.requireNonNull(field, "field 不能为空");
        if (lower > upper) {
            throw new IllegalArgumentException("下界大于上界：" + lower + " > " + upper);
        }
        if (lower == upper && !(includeLower && includeUpper)) {
            throw new IllegalArgumentException("上下界相等但区间是开的，这永远是空集：" + lower);
        }
    }

    /** {@code [lower, upper]} 双闭。 */
    public static LongRangeQuery closed(String field, long lower, long upper) {
        return new LongRangeQuery(field, lower, upper, true, true);
    }

    /** {@code (lower, upper)} 双开。 */
    public static LongRangeQuery open(String field, long lower, long upper) {
        return new LongRangeQuery(field, lower, upper, false, false);
    }

    /** {@code (-∞, upper]}。 */
    public static LongRangeQuery atMost(String field, long upper) {
        return new LongRangeQuery(field, Long.MIN_VALUE, upper, true, true);
    }

    /** {@code [lower, +∞)}。 */
    public static LongRangeQuery atLeast(String field, long lower) {
        return new LongRangeQuery(field, lower, Long.MAX_VALUE, true, true);
    }
}
