package hawk.cloud.indexer;

import hawk.cloud.indexer.dataSource.DataSource;
import hawk.cloud.indexer.dataSource.OriDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 按 {@code hawk.cloud.indexer.data-source.*} 配一个 {@link DataSource} bean。
 *
 * <h2>为什么收成一个 bean</h2>
 * {@link IndexService}（倒排）和 {@link VectorIndexService}（向量）都要从**同一份**语料拉数据。
 * 各自在构造器里建一份的话会拿到两个实例 —— 都指向同一个文件还算凑合，
 * 但 MySQL 那种会各开一套连接池，而且"哪一份配置生效"变得不明显。
 *
 * <p>配错了在**启动时**就抛（{@link IndexService#buildDataSource} 对不认识的 type 直接抛），
 * 不留到第一次请求。
 */
@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(
            @Value("${hawk.cloud.indexer.data-source.type:csv}") String dataSourceType,
            @Value("${hawk.cloud.indexer.data-source.batch-size:1000}") int batchSize,
            @Value("${hawk.cloud.indexer.data-source.file-path:goods.csv}") String filePath,
            @Value("${hawk.cloud.indexer.data-source.skip-header:true}") boolean skipHeader,
            @Value("${hawk.cloud.indexer.data-source.url:}") String mysqlUrl,
            @Value("${hawk.cloud.indexer.data-source.username:}") String mysqlUsername,
            @Value("${hawk.cloud.indexer.data-source.password:}") String mysqlPassword,
            @Value("${hawk.cloud.indexer.data-source.table:}") String mysqlTable,
            @Value("${hawk.cloud.indexer.data-source.shard-column:id}") String shardColumn,
            @Value("${hawk.cloud.indexer.shard-num}") String shardNum) {
        return IndexService.buildDataSource(new IndexService.DataSourceSettings(
                dataSourceType, IndexService.shardIndexOf(shardNum), filePath, batchSize, skipHeader,
                mysqlUrl, mysqlUsername, mysqlPassword, mysqlTable, shardColumn));
    }
}
