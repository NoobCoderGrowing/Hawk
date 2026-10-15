package hawk.recall.query;

/**
 * 查询 AST —— **已经解析完毕**的查询（文本该分的词都分完了）。
 *
 * <h2>与"检索请求"的分界</h2>
 * 这一层只装**已经能拿去查索引**的东西：{@link TermQuery} 里的 term 是词、不是原文。
 * 用户输入的那段还没分词的文本属于**另一层**（"请求"），要先编译才能变成这里的节点 ——
 * 因为分词需要 tokenizer，而 tokenizer 是应用级配置，不该塞进 Searcher。
 *
 * <h2>为什么是 immutable 的 record</h2>
 * 所有实现都是 {@code record}，字段全 final、**没有 setter**。三个直接好处：
 * <ul>
 *   <li><b>可以安全嵌套</b> —— 子查询不会被外层改写（旧实现里 {@code andSearch} 会直接
 *       {@code setScore} 改写子结果的 {@code ScoreDoc}）</li>
 *   <li><b>可以跨线程共享、可以缓存</b></li>
 *   <li>{@code equals/hashCode/toString} 免费，表达式树去重与调试都省事</li>
 * </ul>
 *
 * <p>{@code sealed} 把实现类封死在下面这几种，编译器能对 {@code switch} 做穷尽性检查 ——
 * 将来新增一种查询类型，所有 switch 处会**编译报错**而不是静默走 default。
 */
public sealed interface Query
        permits TermQuery, RangeQuery, LongRangeQuery, BooleanQuery, MatchNoneQuery, MatchAllQuery {
}
