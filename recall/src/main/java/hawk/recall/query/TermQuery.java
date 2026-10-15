package hawk.recall.query;

import java.util.Objects;

/**
 * 词项查询：某个字段等于某个**词**。
 *
 * <p>注意 {@code term} 是**已经分好的词**，不是用户输入的原文 —— 原文要经
 * {@code StringQuery#getTermQueryList(tokenizer)}（或请求层的编译）先摊平。
 */
public record TermQuery(String field, String term) implements Query {

    public TermQuery {
        Objects.requireNonNull(field, "field 不能为空");
        Objects.requireNonNull(term, "term 不能为空");
    }

    /** 便于流式拼装。 */
    public static TermQuery of(String field, String term) {
        return new TermQuery(field, term);
    }
}
