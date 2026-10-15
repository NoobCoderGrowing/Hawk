package demo;

import directory.MMapDirectory;
import document.Document;
import field.StringField;
import hawk.indexer.writer.config.IndexConfig;
import hawk.recall.query.BooleanQuery;
import hawk.recall.query.Occur;
import hawk.recall.query.Query;
import hawk.recall.query.RangeQuery;
import hawk.recall.query.TermQuery;
import hawk.recall.reader.DirectoryReader;
import hawk.recall.request.Condition;
import hawk.recall.request.RequestCompiler;
import hawk.recall.request.Schema;
import hawk.recall.search.ScoreDoc;
import hawk.recall.search.ScoreMode;
import hawk.recall.search.Searcher;
import hawk.recall.search.TopDocs;
import hawk.segment.core.anlyzer.NShortestPathTokenizer;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 召回（检索）示例：怎么写出**复杂**的检索请求。
 *
 * <p>读 {@link BuildIndex} 建出来的 {@code index-data/goods}（先跑它）。
 * 这个索引里可检索的是 {@code title}（文本）和 {@code price}（double）。
 * 工作目录是仓库根。
 *
 * <h2>两层</h2>
 * <pre>
 *   Condition   用户意图：一段**没分词**的文本 + 字段 + 关系     ← 不可检索
 *        │  RequestCompiler.compile(condition)   需要 tokenizer 和 schema，全流程仅此一处
 *        ▼
 *   Query       查询 AST：词已分好，可任意嵌套                   ← 可检索
 *        │  Searcher.search(query, limit, scoreMode)
 *        ▼
 *   TopDocs     命中总数 + 前 N 条
 * </pre>
 *
 * <h2>复杂请求的三件武器</h2>
 * <ol>
 *   <li>{@link Condition.Group} —— <b>条件之间</b>的 AND/OR，可嵌套</li>
 *   <li>{@link Condition.Text} 的第 3、4 个参数 —— <b>字段之间</b> / <b>字段内词与词之间</b></li>
 *   <li>{@link BooleanQuery.Builder} —— 需要 FILTER（只筛不打分）、MUST_NOT（排除）、
 *       minShouldMatch（至少命中几个）时直接手写 AST</li>
 * </ol>
 */
public class RecallDemo {

    public static void main(String[] args) throws Exception {
        DirectoryReader reader = DirectoryReader.open(MMapDirectory.open(Path.of("index-data/goods")));
        var tokenizer = new NShortestPathTokenizer(1);
        Searcher searcher = new Searcher(reader, new IndexConfig(tokenizer));
        RequestCompiler compiler = new RequestCompiler(tokenizer, Schema.from(reader));

        System.out.println("可检索字段: " + Schema.from(reader).describe() + "\n");

        // 键是说明，值是请求。从简单到复杂。
        Map<String, Query> requests = new LinkedHashMap<>();

        // ---- 用 Condition 描述（文本会经过分词）----
        requests.put("标题含「冰糖」",
                compiler.compile(new Condition.Text("冰糖", List.of("title"),
                        Occur.SHOULD, Occur.MUST)));

        requests.put("标题含「冰糖」且价格 ∈ [10,100]",
                compiler.compile(Condition.Group.and(
                        new Condition.Text("冰糖", List.of("title"), Occur.SHOULD, Occur.MUST),
                        Condition.Range.closed("price", 10, 100))));

        requests.put("（标题含「冰糖」或「弯头」）且（价格≤50 或 ≥10000）",
                compiler.compile(Condition.Group.and(
                        Condition.Group.or(
                                new Condition.Text("冰糖", List.of("title"), Occur.SHOULD, Occur.MUST),
                                new Condition.Text("弯头", List.of("title"), Occur.SHOULD, Occur.MUST)),
                        Condition.Group.or(
                                Condition.Range.atMost("price", 50),
                                Condition.Range.atLeast("price", 10000)))));

        // ---- 直接手写 AST（需要 FILTER / MUST_NOT / minShouldMatch）----
        requests.put("标题含「冰糖」，但排除含「儿童」的",
                BooleanQuery.builder()
                        .must(TermQuery.of("title", "冰糖"))
                        .mustNot(TermQuery.of("title", "儿童"))
                        .build());

        requests.put("「冰糖」「老黄」「弯头」里至少命中 2 个",
                BooleanQuery.builder()
                        .should(TermQuery.of("title", "冰糖"))
                        .should(TermQuery.of("title", "老黄"))
                        .should(TermQuery.of("title", "弯头"))
                        .minShouldMatch(2)
                        .build());

        requests.put("标题含「冰糖」，价格 ∈ [10,100] 只筛不打分",
                BooleanQuery.builder()
                        .must(TermQuery.of("title", "冰糖"))
                        .filter(RangeQuery.closed("price", 10, 100))
                        .build());

        for (Map.Entry<String, Query> e : requests.entrySet()) {
            TopDocs hits = searcher.search(e.getValue(), 3, ScoreMode.TOP_SCORES);
            System.out.printf("%-34s 总命中 %,6d  ", e.getKey(), hits.totalHits());
            for (ScoreDoc hit : hits.scoreDocs()) {
                Document doc = searcher.doc(hit);
                String title = ((StringField) doc.getFieldMap().get("title")).getValue();
                System.out.printf("| %.2f %s ", hit.score(), title);
            }
            System.out.println();
        }
        System.out.println();

        // 编译产物长什么样 —— 直接看 AST，就知道"字段关系"和"词关系"分别落在哪一层
        System.out.println("「镀锌弯头」词间 MUST: " + compiler.compile(
                new Condition.Text("镀锌弯头", List.of("title"), Occur.SHOULD, Occur.MUST)));
        System.out.println("「镀锌弯头」词间 SHOULD: " + compiler.compile(
                new Condition.Text("镀锌弯头", List.of("title"), Occur.SHOULD, Occur.SHOULD)));

        // 纯筛选不需要分数，NO_SCORES 省掉整条打分链路
        TopDocs noScore = searcher.search(RangeQuery.atMost("price", 5), 3, ScoreMode.NO_SCORES);
        System.out.printf("%n价格 ≤ 5（NO_SCORES）共 %,d 条，返回 %d 条，分数全为 0%n",
                noScore.totalHits(), noScore.size());

        // 字段名写错在**编译期**就报，不会崩，也不会静默返回空
        try {
            compiler.compile(new Condition.Text("x", List.of("brand"), Occur.SHOULD, Occur.MUST));
        } catch (IllegalArgumentException e) {
            System.out.println("未知字段: " + e.getMessage());
        }
        reader.close();
    }
}
