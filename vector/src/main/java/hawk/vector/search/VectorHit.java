package hawk.vector.search;

/**
 * 一条向量检索命中 —— 对标 {@code recall.search.ScoreDoc}。
 *
 * <p><b>给的是业务主键，不是 ordinal。</b> 这一点是相对旧实现最重要的改动：旧实现只返回
 * ordinal（"向量在索引里的行号"），逼得每个调用方自己去查 {@code ids[ordinal]} 那张表；
 * 多字段之后每张图各有自己的 ordinal 空间，那种映射根本无从写起。既然建索引时就知道主键，
 * 就该在这里换好。
 *
 * @param uniqueId 文档的业务主键（建索引时从 {@code PrimaryKeyField} 取到）
 * @param score    融合后的分数。单字段时就是余弦相似度
 */
public record VectorHit(long uniqueId, float score) {
}
