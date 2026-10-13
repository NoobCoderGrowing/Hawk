package hawk.recall.request;

import hawk.recall.query.BooleanQuery;
import hawk.recall.query.MatchNoneQuery;
import hawk.recall.query.Occur;
import hawk.recall.query.Query;
import hawk.recall.query.RangeQuery;
import hawk.recall.query.TermQuery;
import hawk.segment.core.anlyzer.Analyzer;

import java.util.List;
import java.util.Objects;

/**
 * 把 {@link Condition 检索请求} 编译成 {@link Query 查询 AST}。
 *
 * <p>这一层是**全流程唯一同时需要 analyzer 和 schema 的地方**：
 * <ul>
 *   <li><b>analyzer</b> —— 把 {@link Condition.Text} 里的原文切成词项</li>
 *   <li><b>schema</b> —— 校验字段名，把"用户写错了"在编译期拦下来</li>
 * </ul>
 * 编完之后，{@code Searcher} 拿到的就是一棵纯 AST，既不需要分析器、也不需要 schema。
 *
 * <pre>
 *   var compiler = new RequestCompiler(analyzer, Schema.from(reader));
 *   Query q = compiler.compile(Condition.Group.and(
 *           Condition.Group.or(
 *                   new Condition.Text("老黄冰糖", List.of("title"), Occur.SHOULD, Occur.MUST),
 *                   new Condition.Text("老黄冰糖", List.of("descript"), Occur.SHOULD, Occur.MUST)),
 *           Condition.Range.closed("price", 10, 100)));
 *   TopDocs hits = searcher.search(q, 20, ScoreMode.TOP_SCORES);
 * </pre>
 */
public final class RequestCompiler {

    private final Analyzer analyzer;
    private final Schema schema;

    public RequestCompiler(Analyzer analyzer, Schema schema) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    public Query compile(Condition condition) {
        return switch (condition) {
            case Condition.Text t -> compileText(t);
            case Condition.Range r -> compileRange(r);
            case Condition.Group g -> compileGroup(g);
        };
    }

    /** 文本条件 → 每字段一个词项组，再按 fieldRelation 组合。 */
    private Query compileText(Condition.Text text) {
        BooleanQuery.Builder group = BooleanQuery.builder();
        int compiled = 0;
        for (String field : text.fields()) {
            if (!schema.hasTextField(field)) {
                throw new IllegalArgumentException(
                        "没有可检索的文本字段「" + field + "」。" + schema.describe());
            }
            List<TermQuery> terms = new StringQuery(field, text.text()).getTermQueryList(analyzer);
            if (terms.isEmpty()) {
                // 这段文本在这个字段分不出任何词 —— 该字段无从匹配，跳过它。
                // （注意：若外层是 MUST，跳过等于"少了一个必须满足的条件"；
                //   若全部字段都分不出词，下面直接返回 MatchNone，语义仍然正确。）
                continue;
            }
            BooleanQuery.Builder perField = BooleanQuery.builder();
            for (TermQuery term : terms) {
                perField.add(term, text.termRelation());
            }
            group.add(perField.build(), text.fieldRelation());
            compiled++;
        }
        if (compiled == 0) {
            return MatchNoneQuery.INSTANCE;   // 一条词都没有 → 谁都匹配不到
        }
        BooleanQuery built = group.build();
        // 只有一个字段时不必包一层（单子句的布尔查询是恒等变换）
        return compiled == 1 ? built.clauses().get(0).query() : built;
    }

    /** 数值条件 → {@link RangeQuery}。它天然是筛选，编译到 FILTER 由外层决定。 */
    private Query compileRange(Condition.Range range) {
        if (!schema.hasNumericField(range.field())) {
            throw new IllegalArgumentException(
                    "没有可作范围检索的数值字段「" + range.field() + "」。" + schema.describe());
        }
        return new RangeQuery(range.field(), range.lower(), range.upper(),
                range.includeLower(), range.includeUpper());
    }

    /** 分组 → 按组内关系组合。 */
    private Query compileGroup(Condition.Group group) {
        BooleanQuery.Builder builder = BooleanQuery.builder();
        for (Condition child : group.children()) {
            builder.add(compile(child), group.relation());
        }
        return builder.build();
    }

    /** 编译出来的查询是否需要算分（只有 MUST / SHOULD 子句才需要）。 */
    public static boolean needsScores(Query query) {
        return switch (query) {
            case BooleanQuery b -> b.hasScoringClause();
            case TermQuery ignored -> true;
            case RangeQuery ignored -> false;
            case hawk.recall.query.MatchAllQuery ignored -> false;
            case MatchNoneQuery ignored -> false;
        };
    }
}
