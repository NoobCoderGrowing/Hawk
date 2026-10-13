package hawk.recall.query;

/**
 * 匹配全部文档（分数恒为 0）。
 *
 * <p>用来给"只有 MUST_NOT、没有正向子句"的查询当底座，例如
 * 「除了儿童类，其他都要」：{@code AND[MatchAll, NOT(儿童)]} —— 否则布尔查询没有
 * 任何一个能提供候选集的子句，无从谈起。
 */
public record MatchAllQuery() implements Query {

    public static final MatchAllQuery INSTANCE = new MatchAllQuery();
}
