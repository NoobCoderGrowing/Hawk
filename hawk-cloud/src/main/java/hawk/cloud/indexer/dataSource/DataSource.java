package hawk.cloud.indexer.dataSource;

import java.util.List;

public interface DataSource {

    /**
     * 从**本分片**的第 {@code offset} 条开始，返回至多 {@code batchSize} 条文档。
     *
     * @param offset     本分片内的第几条（从 0 起算，按本分片计数，不是源里的原始位置）
     * @param batchSize  本批最多取多少条；应 ≥ 1
     * @param shardCount 总分片数，≥ 1。实现据此筛出属于自己的文档；
     *                   自己的分片下标由实现自行持有（见类注释）
     * @return 本批文档；<b>本分片已取完时返回空列表</b>，永不返回 {@code null}
     * @throws Exception 读源失败
     */
    List<OriDocument> getBatchDocument(long offset, int batchSize, int shardCount) throws Exception;

    /**
     * 一共要拉几批。
     *
     * <p>不收 {@code batchSize} 参数 —— 它由实现的字段持有（见 {@code FileDataSource}），
     * 所以**服务侧算 offset 时也得知道同一个值**，否则批数和翻页会对不上。
     *
     * @return {@code ceil(行数 / batchSize)}；源为空时返回 0
     * @throws Exception 读源失败
     */
    int getTotalBatchCount() throws Exception;
}
