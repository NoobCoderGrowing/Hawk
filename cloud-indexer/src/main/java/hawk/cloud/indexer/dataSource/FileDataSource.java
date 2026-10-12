package hawk.cloud.indexer.dataSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 从本地文件读文档的数据源（制表符分隔，如 {@code goods.csv}）。
 *
 * <p>目前只实现了 {@link #getTotalBatchCount}；{@link #getBatchDocument} 还是空壳。
 *
 * <h2>batchSize 从哪来</h2>
 * 它是本类的**字段**，所以 {@link #getTotalBatchCount} 只收 {@code skipHeader}，
 * 行数除以字段里的 batchSize 得到批数。构造时用带 batchSize 的那个重载给它赋值；
 * 只给路径时它是 0，{@link #getTotalBatchCount} 会明确报错而不是抛 {@code / by zero}。
 *
 * <h2>skipHeader</h2>
 * {@code true} 就**直接丢掉第一行，不看内容**。注意这与
 * {@code benchmark.CorpusLoader} / {@code demo.BuildIndex} 等处读取循环的"只有确实像表头
 * （{@code uniqueID\t} 开头）才跳"**不一致** —— 对现在带表头的 goods.csv 两者结果相同，
 * 但表头一旦被去掉，这里会少算一行而那边不会，行数差 1 会让分页错位。
 */
public class FileDataSource implements DataSource {

    private String filePath;
    private int batchSize;
    private boolean skipHeader;



    /** 带上每批条数。{@link #getTotalBatchCount} 需要它。 */
    public FileDataSource(String filePath, int batchSize, boolean skipHeader) {
        this.filePath = filePath;
        this.batchSize = batchSize;
        this.skipHeader = skipHeader;
    }

    /**
     * 一共要拉几批：{@code ceil(行数 / batchSize)}。
     *
     * @param skipHeader true 则直接丢掉第一行（表头），不看内容
     * @return 批数；文件只有表头或为空时返回 0
     * @throws IOException           读文件失败
     * @throws IllegalStateException batchSize 没设（用了不带 batchSize 的构造器）
     */
    public int getTotalBatchCount() throws IOException {
        if (batchSize < 1) {
            // 用单参构造器时 batchSize 是 0。直接除会抛 "/ by zero"，报错信息看不出原因，
            // 所以在这里给个说得清的说法。
            throw new IllegalStateException(
                    "batchSize 未设置（当前为 " + batchSize + "）：请用 new FileDataSource(path, batchSize) 构造");
        }
        long lines = countLines(skipHeader);
        // 向上取整。(lines + batchSize - 1) / batchSize 是整数运算；写成 Math.ceil(lines / batchSize)
        // 是错的 —— 那是整数除法，结果已经精确，ceil 不起作用。
        return (int) ((lines + batchSize - 1) / batchSize);
    }

    /** 数行：{@code skipHeader} 时硬跳首行；空行也计入（"多少行"的字面意思）。 */
    private long countLines(boolean skipHeader) throws IOException {
        long lines = 0;
        try (BufferedReader reader = Files.newBufferedReader(Path.of(filePath), StandardCharsets.UTF_8)) {
            if (skipHeader) {
                reader.readLine();
            }
            while (reader.readLine() != null) {
                lines++;
            }
        }
        return lines;
    }

    @Override
    public List<OriDocument> getBatchDocument(long offset, int batchSize, int shardCount) throws Exception {
        return null;
    }
}
