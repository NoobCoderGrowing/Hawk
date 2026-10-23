package hawk.recall.request;

import hawk.recall.query.TermQuery;
import hawk.segment.core.Term;
import hawk.segment.core.anlyzer.Tokenizer;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 文本查询：把一段自然语言文本分词成一串 {@link hawk.recall.query.TermQuery 词项}。
 *
 * <p><b>它本身不可检索</b> —— 它属于"请求层"，装的是**还没分词的原文**。
 * 要检索得先 {@link #getTermQueryList(List)} 摊平，或交给
 * {@link RequestCompiler} 编译成查询 AST。
 *
 * <p>之所以把它和"查询 AST"分开：分词需要 tokenizer，而 tokenizer 是应用级配置。
 * 混成一个类（旧实现就是这样）会逼得 Searcher 也得持有 tokenizer，
 * 而且这个对象既分不了词、又没法参与嵌套。
 */
@Data
public class StringQuery {

    private String field;
    private String value;


    public StringQuery(String fieldName, String value) {
        this.field = fieldName;
        this.value = value;
    }

    /**
     * 用**一组**分词器把文本摊平成一串词项查询，并**按词去重**。
     *
     * <h2>为什么收一组，而不是一个</h2>
     * 索引时同一个字段可以用多个 tokenizer 分词（结果取并集入倒排），所以查询侧必须用
     * **同一组** —— 否则某个 tokenizer 独有的切法，在倒排里明明有词却永远问不到（不报错，只是搜不到）。
     * 用哪一组由**索引**说了算（fdm 里每个字段都记着，见 {@code DESIGN.md} §6.7），
     * 不是这里或调用方拍脑袋定的。
     *
     * <h2>为什么必须去重</h2>
     * 多个 tokenizer 常常切出同一个词（比如都切出「冰糖」）。不去重就会产生多个**一模一样**的
     * {@link TermQuery}，而 {@link hawk.recall.query.BooleanQuery} **不做任何子句去重**：
     * 重复的 MUST 子句会让 {@code ConjunctionScorer} 把 BM25 分数**求和**（分数翻倍），
     * 重复的 SHOULD 子句还会把 {@code matchCount} 虚增（影响 {@code minShouldMatch}）。
     *
     * <p>按词去重是**零损失**的：{@code Term.pos} 在检索路径上不被任何东西读
     * （不进 {@link TermQuery}、不被 {@code TermScorer} 读、也没有落盘）。
     *
     * <p><b>分词器必须从外面传进来</b>，不能在这里自己 new：{@code NShortestPathTokenizer}
     * 的构造要读 62 万条的词典文件（{@code data/npathword.data}），每次查询都新建一遍是灾难。
     * 调用方（{@link RequestCompiler}）手里已经有从索引里解析出来的那一组。
     *
     * @param tokenizers 该字段在**索引里记录**的那一组分词器，不可为空
     * @return 去重后的词项查询；文本为空时返回**空列表**（不是 null）
     */
    public List<TermQuery> getTermQueryList(List<Tokenizer> tokenizers) {
        Objects.requireNonNull(tokenizers, "tokenizers");
        if (tokenizers.isEmpty()) {
            // 走到这里说明调用方没先问索引要那一组 —— 是 bug，不是用户错误
            throw new IllegalArgumentException(
                    "字段「" + field + "」没有可用的 tokenizer，调用方应当先问索引要那一组");
        }
        // LinkedHashSet：按首次出现的顺序去重，结果稳定可复现
        // （HashSet 的顺序不保证，会让同一请求两次的 clause 顺序不同，排错时很迷惑）
        Set<String> terms = new LinkedHashSet<>();
        for (Tokenizer tokenizer : tokenizers) {
            for (Term term : tokenizer.tokenize(value, field)) {
                terms.add(term.getValue());
            }
        }
        List<TermQuery> result = new ArrayList<>(terms.size());
        for (String term : terms) {
            result.add(new TermQuery(field, term));
        }
        return result;
    }
}
