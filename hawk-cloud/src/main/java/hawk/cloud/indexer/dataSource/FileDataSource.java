package hawk.cloud.indexer.dataSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 从本地文件读文档的数据源（制表符分隔，如 {@code goods.csv}）。
 *
 * <p>字段名取自**第一行**（制表符分隔的列名）。{@code skipHeader} 决定第一行算不算数据：
 * {@code true} 时它是纯表头，数据从第二行起；{@code false} 时它既是数据的来源、也是数据本身。
 * 这与 {@link #getTotalBatchCount} 的"数多少行"用的是同一个口径，所以分页不会错位。
 *
 * <h2>batchSize 从哪来</h2>
 * 它是本类的**字段**，所以 {@link #getTotalBatchCount} 只收 {@code skipHeader}，
 * 行数除以字段里的 batchSize 得到批数。构造时用带 batchSize 的那个重载给它赋值；
 * 只给路径时它是 0，{@link #getTotalBatchCount} 会明确报错而不是抛 {@code / by zero}。
 *
 * <h2>skipHeader</h2>
 * {@code true} 就**直接丢掉第一行，不看内容**。注意这与
 * {@code demo.BuildIndex} 等处读取循环的"只有确实像表头
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

    /**
     * 取本批文档：第 {@code [offset, offset+batchSize)} 行。
     *
     * <p><b>不做分片过滤</b> —— 这里返回的是窗口内的**全部**行，由 {@code IndexService}
     * 用 {@code belongsToShard} 筛。理由有两条：一是 {@link #getTotalBatchCount} 数的是
     * 全部行数，offset 若按"本分片的第几条"来算，两边口径就对不上；二是分片规则应当只有
     * 一处（Java 那边），数据源只管读。
     *
     * <p>所有值都是**字符串**（CSV 没有类型），装进 {@link OriDocument} 时类型名统一记
     * {@code "String"}。{@code IndexService.toField} 本来就把"能解析成数字的字符串"当数字收，
     * 所以 price 这类列照样能建 BKD。
     *
     * <p>契约：本批取完（或文件到头）返回**空列表**，永不返回 {@code null}。
     */
    @Override
    public List<OriDocument> getBatchDocument(long offset, int batchSize, int shardCount) throws Exception {
        List<OriDocument> batch = new ArrayList<>(Math.max(batchSize, 0));
        try (BufferedReader reader = Files.newBufferedReader(Path.of(filePath), StandardCharsets.UTF_8)) {
            String first = reader.readLine();
            if (first == null) {
                return batch;                       // 空文件
            }
            String[] names = first.split("\t", -1);   // 第一行是列名（skipHeader=false 时它也是数据）
            if (!skipHeader) {
                batch.add(toOriDocument(names, names));
            }
            long index = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (index >= offset && batch.size() < batchSize) {
                    String[] cells = line.split("\t", -1);
                    if (cells.length >= 2) {        // 少于两列的行不是文档（与 demo.BuildIndex 一致）
                        batch.add(toOriDocument(names, cells));
                    }
                }
                index++;
                if (index >= offset + batchSize) {
                    break;                          // 本批够了，不再往下读
                }
            }
        }
        return batch;
    }

    /** 一行的各列按位置对上列名。列数不一致时，多出来的列按 {@code colN} 命名。 */
    private static OriDocument toOriDocument(String[] names, String[] cells) {
        OriDocument doc = new OriDocument();
        for (int i = 0; i < cells.length; i++) {
            String name = i < names.length ? names[i] : ("col" + i);
            doc.add(name, cells[i], "String");
        }
        return doc;
    }
}
