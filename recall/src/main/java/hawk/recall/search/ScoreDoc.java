package hawk.recall.search;

/**
 * 一条命中：文档的**内部 docID** 与分数。
 *
 * <p>不可变。注意 {@code docId} 是索引内部位置，**不是业务主键** —— 两者数值上毫无关系
 * （docID 按索引线程的完成顺序分配）。要取业务主键用 {@code Searcher.docByUniqueId}。
 *
 * <p>{@code ScoreMode.NO_SCORES} 时 {@code score} 恒为 0。
 *
 * <p>旧实现把 {@code equals/hashCode} 定成"只看 docID"（用来做去重）。
 * 这里不去定义它们 —— 它是**一条结果**，不是"一个文档"的同义词；
 * 去重是执行层的事，不该塞进这个值类型里（那会让两个分数不同的同 docID 结果"相等"，
 * 掩盖真问题）。
 */
public record ScoreDoc(int docId, float score) {

    public static ScoreDoc of(int docId, float score) {
        return new ScoreDoc(docId, score);
    }

    /** 只要 docID、不要分数时的便捷构造。 */
    public static ScoreDoc noScore(int docId) {
        return new ScoreDoc(docId, 0f);
    }
}
