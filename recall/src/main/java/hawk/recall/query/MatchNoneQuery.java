package hawk.recall.query;

/**
 * 一条都不匹配。
 *
 * <p>存在的意义是让布尔查询的**空集情形有个显式的表达**，而不是靠"返回 null"来暗示。
 * 旧实现里 {@code andSearch}/{@code orSearch} 用 {@code null} 表示"没有结果"，
 * 于是"空结果"和"调用方传了 null"混在一起，调用方必须到处判空。
 */
public record MatchNoneQuery() implements Query {

    public static final MatchNoneQuery INSTANCE = new MatchNoneQuery();
}
