package hawk.recall.query;

/**
 * 布尔查询里一个子句的**角色** —— 决定它"要不要匹配"和"要不要算分"这两件事。
 *
 * <p>拆成四种而不是两种（Hawk 原先只有 MUST/SHOULD），关键是把
 * "必须匹配"和"参与打分"**分开**：
 *
 * <table border="1">
 *   <caption>四种角色</caption>
 *   <tr><th>角色</th><th>必须匹配</th><th>参与打分</th><th>典型用途</th></tr>
 *   <tr><td>{@link #MUST}</td>    <td>是</td><td>是</td><td>关键词</td></tr>
 *   <tr><td>{@link #FILTER}</td>  <td>是</td><td>否</td><td>数值区间、精确筛选</td></tr>
 *   <tr><td>{@link #SHOULD}</td>  <td>否</td><td>是</td><td>可选加分项（"任一命中"）</td></tr>
 *   <tr><td>{@link #MUST_NOT}</td><td>否（必须不匹配）</td><td>否</td><td>排除词</td></tr>
 * </table>
 *
 * <p>{@link #FILTER} 的价值在性能：数值区间这类查询的分数恒为 0，
 * 让它们参与打分是纯浪费 —— 配上 {@code ScoreMode.NO_SCORES} 可以把整条链路的分数量算省掉。
 */
public enum Occur {

    /** 必须匹配，且参与打分。 */
    MUST,

    /** 必须匹配，但**不**参与打分 —— 纯筛选。 */
    FILTER,

    /** 匹配则加分；一个布尔查询里至少要满足 {@code minShouldMatch} 个。 */
    SHOULD,

    /** 必须**不**匹配。 */
    MUST_NOT;

    /** 是否要求文档匹配这个子句（MUST / FILTER 要求，SHOULD / MUST_NOT 不要求）。 */
    public boolean isRequired() {
        return this == MUST || this == FILTER;
    }

    /** 是否参与打分（只有 MUST / SHOULD 参与）。 */
    public boolean scores() {
        return this == MUST || this == SHOULD;
    }
}
