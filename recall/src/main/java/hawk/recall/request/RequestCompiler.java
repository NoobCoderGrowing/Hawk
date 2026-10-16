package hawk.recall.request;

import hawk.recall.query.BooleanQuery;
import hawk.recall.query.LongRangeQuery;
import hawk.recall.query.MatchNoneQuery;
import hawk.recall.query.Occur;
import hawk.recall.query.Query;
import hawk.recall.query.RangeQuery;
import hawk.recall.query.TermQuery;
import hawk.recall.reader.DirectoryReader;
import hawk.segment.core.anlyzer.Tokenizer;
import hawk.segment.core.anlyzer.TokenizerRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把 {@link Condition 检索请求} 编译成 {@link Query 查询 AST}。
 *
 * <p>这一层是**全流程唯一同时需要 tokenizer 和 schema 的地方**：
 * <ul>
 *   <li><b>tokenizer</b> —— 把 {@link Condition.Text} 里的原文切成词项。<b>按字段各有一组</b>：
 *       索引时某个字段用了哪些 tokenizer，查询时就得用同一组，否则切出来的词在倒排里不存在
 *       （不报错，只是静默搜不到）。这一组由调用方从**索引**里读出来（fdm 每字段记着）。</li>
 *   <li><b>schema</b> —— 校验字段名，把"用户写错了"在编译期拦下来</li>
 * </ul>
 * 编完之后，{@code Searcher} 拿到的就是一棵纯 AST，既不需要分析器、也不需要 schema。
 *
 * <pre>
 *   var compiler = new RequestCompiler(tokenizers, Schema.from(reader));
 *   Query q = compiler.compile(Condition.Group.and(
 *           Condition.Group.or(
 *                   new Condition.Text("老黄冰糖", List.of("title"), Occur.SHOULD, Occur.MUST),
 *                   new Condition.Text("老黄冰糖", List.of("descript"), Occur.SHOULD, Occur.MUST)),
 *           Condition.Range.closed("price", 10, 100)));
 *   TopDocs hits = searcher.search(q, 20, ScoreMode.TOP_SCORES);
 * </pre>
 */
public final class RequestCompiler {

    /** 字段名 → 该字段在**索引里记录**的那一组分词器。 */
    private final Map<String, List<Tokenizer>> tokenizers;
    private final Schema schema;

    public RequestCompiler(Map<String, List<Tokenizer>> tokenizers, Schema schema) {
        this.tokenizers = Objects.requireNonNull(tokenizers, "tokenizers");
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    /**
     * 按**索引里记的**配置建一个编译器：字段 → 那一组分词器从 fdm 读出来
     * （{@link DirectoryReader#getFieldTokenizers()}），再经 {@link TokenizerRegistry} 解析成实例。
     *
     * <p><b>为什么以索引为准，而不是让调用方写死一份配置</b>：只有用建索引时那一组分词，
     * 查询切出来的词才可能在倒排里存在。配置与索引对不上时的失败形态是**静默搜不到** ——
     * 不报错、不抛异常，只是召回率下降，最难查。
     *
     * <p>实例由注册表缓存共享，所以同时持有多个版本快照也不会各读一遍 5.87 MB 词典。
     */
    public static RequestCompiler forReader(DirectoryReader reader) {
        Map<String, List<Tokenizer>> byField = new HashMap<>();
        reader.getFieldTokenizers().forEach((field, names) -> {
            if (names.isEmpty()) {
                return;   // 没被分词的字段（数值 / 只存原文），Schema 也不会把它算作可检索字段
            }
            List<Tokenizer> instances = new ArrayList<>(names.size());
            for (String name : names) {
                instances.add(TokenizerRegistry.get(name));
            }
            byField.put(field, instances);
        });
        return new RequestCompiler(byField, Schema.from(reader));
    }

    public Query compile(Condition condition) {
        return switch (condition) {
            case Condition.Text t -> compileText(t);
            case Condition.Range r -> compileRange(r);
            case Condition.LongRange r -> compileLongRange(r);
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
            List<Tokenizer> fieldTokenizers = tokenizers.get(field);
            if (fieldTokenizers == null || fieldTokenizers.isEmpty()) {
                // schema 说这个字段可检索，索引的 fdm 却没记它的 tokenizer 组 —— 不该发生。
                // 抛错而不是回退到某个默认分词器：回退会切出索引里不存在的词，**静默搜不到**，
                // 正是这个改动要消灭的那种失败模式。
                throw new IllegalStateException("字段「" + field
                        + "」在索引里没有记录 tokenizer 组，索引可能已损坏，请重建。");
            }
            List<TermQuery> terms = new StringQuery(field, text.text()).getTermQueryList(fieldTokenizers);
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

    /**
     * double 区间 → {@link RangeQuery}。
     *
     * <p>校验查的是 **double 字段**而不是"任意数值字段" —— 两种数值字段的落盘编码不同，
     * 拿 double 的区间去查 long 字段比较的是两套编码的 long，结果会是垃圾。
     */
    private Query compileRange(Condition.Range range) {
        if (!schema.hasDoubleField(range.field())) {
            throw new IllegalArgumentException(
                    "没有可作 double 范围检索的字段「" + range.field() + "」。" + schema.describe());
        }
        return new RangeQuery(range.field(), range.lower(), range.upper(),
                range.includeLower(), range.includeUpper());
    }

    /** long 区间 → {@link LongRangeQuery}。校验查的是 **long 字段**，理由同上。 */
    private Query compileLongRange(Condition.LongRange range) {
        if (!schema.hasLongField(range.field())) {
            throw new IllegalArgumentException(
                    "没有可作 long 范围检索的字段「" + range.field() + "」。" + schema.describe());
        }
        return new LongRangeQuery(range.field(), range.lower(), range.upper(),
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
            case LongRangeQuery ignored -> false;
            case hawk.recall.query.MatchAllQuery ignored -> false;
            case MatchNoneQuery ignored -> false;
        };
    }
}
