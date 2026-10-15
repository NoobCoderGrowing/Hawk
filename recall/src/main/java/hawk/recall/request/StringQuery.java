package hawk.recall.request;

import hawk.recall.query.TermQuery;
import hawk.segment.core.Term;
import hawk.segment.core.anlyzer.Tokenizer;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 文本查询：把一段自然语言文本分词成一串 {@link hawk.recall.query.TermQuery 词项}。
 *
 * <p><b>它本身不可检索</b> —— 它属于"请求层"，装的是**还没分词的原文**。
 * 要检索得先 {@link #getTermQueryList(Tokenizer)} 摊平，或交给
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
     * 调用分词器，把文本摊平成一串词项查询。
     *
     * <p><b>分词器必须从外面传进来</b>，不能在这里自己 new：{@code NShortestPathTokenizer}
     * 的构造要读 62 万条的词典文件（{@code data/npathword.data}），每次查询都新建一遍是灾难。
     * 调用方手里一般已经有了。
     *
     * @param tokenizer 分词器，决定同一段文本会被切成哪些词
     * @return 与分词器输出同序的词项查询；文本为空时返回**空列表**（不是 null）
     */
    public List<TermQuery> getTermQueryList(Tokenizer tokenizer) {
        Objects.requireNonNull(tokenizer, "tokenizer");
        List<TermQuery> result = new ArrayList<>();
        for (Term term : tokenizer.tokenize(value, field)) {
            result.add(new TermQuery(term.getFieldName(), term.getValue()));
        }
        return result;
    }
}
