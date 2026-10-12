package hawk.cloud.indexer;

import hawk.cloud.indexer.dataSource.DataSource;
import hawk.cloud.indexer.dataSource.FileDataSource;
import hawk.cloud.indexer.dataSource.MySqlDataSource;
import hawk.cloud.indexer.dataSource.OriDocument;

import directory.MMapDirectory;
import document.Document;
import field.Field;
import field.NumberField;
import field.PrimaryKeyField;
import field.StringField;
import hawk.indexer.writer.IndexWriter;
import hawk.indexer.writer.config.IndexConfig;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 建索引的流程编排：从 {@link DataSource} 按批拉**原始文档**，挑出属于本分片的，
 * 按 fieldList 装成 {@link Document} 并交给 {@link IndexWriter} 写进索引。
 *
 * <h2>流程</h2>
 * <ol>
 *   <li>从 fieldList 里找出主键字段名 —— 算分片要靠它，找不着一开始就停下；</li>
 *   <li>开一个 {@link IndexWriter}（挂到已有索引上，不清空目录）；</li>
 *   <li>循环每一批，算 {@code offset = i * batchSize} 取一批 {@link OriDocument}；</li>
 *   <li>逐条：取主键做 {@code floorMod(pk, shardCount)}，等于本节点分片下标才要；</li>
 *   <li>要的那些按 {@code fieldList} 装成 {@link Document}，交给 {@link #addToIndex}；</li>
 *   <li>循环结束 {@code commit()}，并把 writer 的线程池关掉。</li>
 * </ol>
 *
 * <h2>几个必须知道的前提</h2>
 * <ul>
 *   <li><b>实际输出目录 = {@code index-dir}/{shard-num}/{当日日期}</b>，如
 *       {@code cloud/text-index/shard-0/2026-10-13}。按分片、按日期分开写，
 *       不会碰到 web 演示用的 {@code index-data/goods}。</li>
 *   <li>用 {@code loadExistingPkMap=true} 是"挂到已有索引"那条路，**不清空目录**。
 *       {@code PkMapStore.load} 在文件不存在时返回空 map，所以空目录上也安全。</li>
 *   <li>重复调用会往同一份索引里**再写一遍**，没有幂等保护 —— 同一份语料连调两次就是两份。
 *       （追加后 docID 如何分配属于 indexer 模块内部的语义，这里没有验证过。）</li>
 *   <li>analyzer 写死 {@code NShortestPathAnalyzer(1)}，与 {@code demo.BuildIndex} 一致；
 *       它状态里有可变的 HashSet，所以**每次调用新建一个**，不跨请求复用。</li>
 * </ul>
 */
@Service
public class IndexService {

    private static final Logger log = LoggerFactory.getLogger(IndexService.class);

    private final DataSource dataSource;

    /** 索引建完后通知协调器（尽力而为，失败只记日志）。 */
    private final CoordinatorNotifier coordinatorNotifier;

    /** 每批多少条。构造时从配置读入 —— 既用于算 offset，也传给数据源，保证两边一致。 */
    private final int batchSize;

    /** 本节点的分片名，形如 {@code shard-0}。取模要的是它后面那个数字。构造时从配置读入。 */
    private final String shardNum;

    /** 本节点角色，形如 {@code text-indexer}。进索引目录的第二段，见 {@link #indexDirOf}。 */
    @Value("${hawk.cloud.indexer.role}")
    private String role;

    /** 索引**根**目录。实际输出目录 = 它 / 角色 / 分片名 / 当日日期，见 {@link #indexDirOf}。 */
    @Value("${hawk.cloud.indexer.index-dir:cloud/index}")
    private String indexDirRoot;

    /**
     * 索引线程数，传给 {@link IndexConfig} —— 它决定 {@link IndexWriter} 内部那个线程池的大小。
     *
     * <p>不显式给的话 {@code IndexConfig(analyzer)} 会用 {@code Constants.PROCESSOR_NUM}（CPU 核数），
     * 在核多的机器上会把并发拉满；所以这里默认给 3 并做成可配。
     */
    @Value("${hawk.cloud.indexer.indexer-threads:3}")
    private int indexerThreads;

    /**
     * {@code data-source} 段的全部配置。
     *
     * <p>收成一个 record 是因为摊开成方法参数已经有十个，看不出哪个属于哪种数据源。
     * <b>别把它交给 fastjson</b> —— record 会被静默序列化成 {@code {}}（虽然它也不走 HTTP）。
     *
     * @param myShardIndex 本节点分片下标，由 {@code shard-num} 解析而来（数据库源要拿它做 SQL 取模）
     */
    public record DataSourceSettings(String type, int myShardIndex,
                                     String filePath, int batchSize, boolean skipHeader,
                                     String url, String username, String password,
                                     String table, String shardColumn) {
    }

    /**
     * 按配置构造：{@code data-source.type} 决定用哪个数据源实现，其余子键是它的入参。
     *
     * <p>不是注入一个 {@code DataSource} bean 而是自己按配置挑 —— 源的类型本来就是这份配置的
     * 一部分，放在这里"配了什么就用什么"看得见；代价是加新实现要改 {@link #buildDataSource}。
     */
    public IndexService(
            CoordinatorNotifier coordinatorNotifier,
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
        this.batchSize = batchSize;
        this.shardNum = shardNum;
        this.coordinatorNotifier = coordinatorNotifier;
        this.dataSource = buildDataSource(new DataSourceSettings(
                dataSourceType, shardIndexOf(shardNum), filePath, batchSize, skipHeader,
                mysqlUrl, mysqlUsername, mysqlPassword, mysqlTable, shardColumn));
    }

    /**
     * 按 {@code data-source.type} 的值挑数据源实现。加新类型（远端接口…）时在这里加一个 case。
     *
     * <p>不认识的值**启动就抛**，不留到运行时 —— 配错了应该在服务起不来的时候就发现。
     *
     * @throws IllegalStateException {@code data-source.type} 不是已支持的类型
     */
    static DataSource buildDataSource(DataSourceSettings s) {
        String type = s.type() == null ? "" : s.type().trim().toLowerCase();
        return switch (type) {
            case "csv" -> new FileDataSource(s.filePath(), s.batchSize(), s.skipHeader());
            case "mysql" -> new MySqlDataSource(s.url(), s.username(), s.password(),
                    s.table(), s.shardColumn(), s.myShardIndex(), s.batchSize());
            default -> throw new IllegalStateException(
                    "不认识的数据源类型：“" + s.type() + "”（目前只支持 csv / mysql）"
                            + " —— 在 IndexService.buildDataSource 里加一个 case");
        };
    }

    /**
     * 按批拉原始文档，挑出属于本分片的，装成文档并写进索引。
     *
     * @param shardCount 总分片数
     * @param fieldList  要索引/存储的字段定义，决定每个字段是否分词、是否存储
     */
    public void index(int shardCount, List<Field> fieldList) throws Exception {
        int myShardIndex = shardIndexOf(shardNum);

        String primaryKeyField = primaryKeyFieldName(fieldList);
        if (primaryKeyField == null) {
            // 没有主键就算不出文档归哪个分片，后续每一步都做不了 —— 与其每条文档刷一条警告，不如直接停。
            log.error("fieldList 里没有 PrimaryKeyField 定义，无法判断文档归属哪个分片，索引中止。字段：{}",
                    fieldList.stream().map(Field::getName).toList());
            return;
        }

        int batchCount = dataSource.getTotalBatchCount();
        Path indexDirPath = indexDirOf(indexDirRoot, role, shardNum, LocalDate.now());
        Files.createDirectories(indexDirPath);

        // 第三个参数 true = 把已有的 pk.map 读进来，也就是"挂到已有索引上"，不清空目录。
        // 空目录也安全：PkMapStore.load 在文件不存在时返回空 map。
        // 把请求里认定的主键字段名传给 IndexConfig —— 索引器不再假定它叫 "uniqueID"，
        // 数据源（数据库列名等）叫什么都行，只要请求的 fieldList 说清楚即可。
        IndexWriter writer = new IndexWriter(
                new IndexConfig(new NShortestPathAnalyzer(1), indexerThreads, primaryKeyField),
                MMapDirectory.open(indexDirPath),
                true);

        log.info("开始索引：shardCount={}，本节点分片下标={}（shard-num={}），batchSize={}，共 {} 批，"
                        + "主键字段={}，索引线程={}，输出目录={}，字段 {}",
                shardCount, myShardIndex, shardNum, batchSize, batchCount, primaryKeyField,
                indexerThreads, indexDirPath.toAbsolutePath(),
                fieldList.stream().map(Field::getName).toList());

        long scanned = 0;
        long accepted = 0;
        long otherShard = 0;
        long noPrimaryKey = 0;

        try {
            for (int i = 0; i < batchCount; i++) {
                long offset = (long) i * batchSize;
                List<OriDocument> batch = dataSource.getBatchDocument(offset, batchSize, shardCount);

                if (batch == null) {
                    // 接口契约写着"永不返回 null"。真拿到 null 说明实现没遵守，
                    // 继续循环只会一路 NPE，所以明确记一条再中断。
                    log.warn("第 {}/{} 批（offset={}）返回 null —— 契约规定永不返回 null。"
                            + "当前 FileDataSource.getBatchDocument 还是空壳，循环就此中断。",
                            i + 1, batchCount, offset);
                    break;
                }
                if (batch.isEmpty()) {
                    // 契约：空列表 = 本分片已取完。提前收工，不必把剩下的批次空跑一遍。
                    log.info("第 {}/{} 批为空，视为取完，提前结束", i + 1, batchCount);
                    break;
                }

                for (OriDocument raw : batch) {
                    scanned++;
                    Long primaryKey = primaryKeyOf(raw, primaryKeyField);
                    if (primaryKey == null) {
                        noPrimaryKey++;
                        log.warn("原始文档没有可用的主键值（字段 {}），算不出分片，跳过：{}", primaryKeyField, raw);
                        continue;
                    }
                    if (!belongsToShard(primaryKey, shardCount, myShardIndex)) {
                        otherShard++;
                        continue;
                    }
                    Document forIndex = rebuildDocument(raw, fieldList);
                    writer.addDoc(forIndex);
                    accepted++;
                    log.debug("收下主键 {}：原始字段 {} -> 索引字段 {}",
                            primaryKey, raw.getFields().keySet(), forIndex.getFieldMap().keySet());
                }

                log.info("第 {}/{} 批：offset={}，累计 扫过 {} / 写入 {} / 非本分片 {} / 无主键 {}",
                        i + 1, batchCount, offset, scanned, accepted, otherShard, noPrimaryKey);
            }

            writer.commit();
        } finally {
            // IndexWriter 没有 close()，但它构造时起了一个 ThreadPoolExecutor（线程数是
            // indexerThreadNum）。不关掉的话每个请求都漏一组线程 —— BuildIndex 是一次性进程所以
            // 从没暴露过这个问题。
            writer.getThreadPoolExecutor().shutdown();
        }

        log.info("索引结束：扫过 {} 条，写入 {} 条，非本分片 {} 条，无主键 {} 条 -> {}",
                scanned, accepted, otherShard, noPrimaryKey, indexDirPath.toAbsolutePath());

        // 走到这里说明 commit 已经成功 —— 通知协调器"这个 role 的这个分片建好了、路径在哪"。
        // 位置放在 finally 之后：commit 抛异常时不会执行到这里，也就不会发出成功的通知。
        // 通知本身尽力而为，失败只记日志（见 CoordinatorNotifier）。
        coordinatorNotifier.indexComplete(role, shardNum, indexDirPath.toAbsolutePath().toString());
    }

    /**
     * 索引目录 = {@code 根目录 / 角色 / 分片名 / 当日日期}，如
     * {@code cloud/index/text-indexer/shard-0/2026-10-13}。
     *
     * <p>角色在最外层（紧跟根目录）：同一个节点上四类索引器各写各的目录树，互不干涉；
     * 再往下按分片、按日期分 —— 本节点只写自己那份，也不会把不同天的索引混在一起。
     * 日期用 ISO 格式（{@code yyyy-MM-dd}）—— 目录名可读、且字典序即时间序。
     *
     * <p>静态以便单测（日期从参数进来，不在这里调 {@code now()}）。
     */
    static Path indexDirOf(String root, String role, String shardNum, LocalDate date) {
        return Path.of(root, role, shardNum, date.format(DateTimeFormatter.ISO_LOCAL_DATE));
    }

    /**
     * 分片名 {@code shard-N} → 下标 N。分片名是给注册中心登记用的，取模要的是数字。
     *
     * @throws IllegalStateException 名字不是 {@code shard-<数字>} 的形式
     */
    static int shardIndexOf(String shardNum) {
        String digits = shardNum == null ? "" : shardNum.replaceFirst("^shard-", "");
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "shard-num 应当是 shard-<数字> 的形式，实际为“" + shardNum + "”", e);
        }
    }

    /**
     * 主键是否归本分片。
     *
     * <p>用 {@link Math#floorMod} 而不是 {@code %}：主键若为负，{@code %} 会给出负余数，
     * 那样负主键的文档永远轮不到分片 0 之外的分片。
     */
    static boolean belongsToShard(long primaryKey, int shardCount, int myShardIndex) {
        return Math.floorMod(primaryKey, shardCount) == myShardIndex;
    }

    /** fieldList 里 {@link PrimaryKeyField} 的名字；没定义主键时返回 null。 */
    static String primaryKeyFieldName(List<Field> fieldList) {
        for (Field def : fieldList) {
            if (def instanceof PrimaryKeyField) {
                return def.getName();
            }
        }
        return null;
    }

    /** 取原始文档里的主键值；缺失或转不成数字时返回 null。 */
    static Long primaryKeyOf(OriDocument raw, String primaryKeyField) {
        Object value = raw.value(primaryKeyField);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 按 {@code fieldList} 装成索引用的文档：**值取自原始文档，isTokenized / isStored 取自定义**。
     *
     * <p>fieldList 没列的字段不进结果 —— 它同时是"要哪些字段"的白名单。
     */
    static Document rebuildDocument(OriDocument raw, List<Field> fieldList) {
        Document out = new Document();
        for (Field def : fieldList) {
            if (!raw.hasField(def.getName())) {
                continue;   // 源里压根没有这个字段
            }
            Field built = toField(def, raw.value(def.getName()));
            if (built != null) {
                out.add(built);
            }
        }
        return out;
    }

    /**
     * 把原始值按定义装成一个 {@link Field}。
     *
     * <p>值为空时整条跳过 —— 不能让它带着走：{@code StringField.customSerialize()} 会对 null 调
     * {@code getBytes()}，写索引时直接 NPE。
     */
    static Field toField(Field def, Object value) {
        String name = def.getName();
        if (value == null) {
            log.debug("字段 {} 的原始值为空，跳过", name);
            return null;
        }
        try {
            if (def instanceof StringField) {
                return new StringField(name, String.valueOf(value), def.isTokenized(), def.isStored());
            }
            if (def instanceof NumberField) {
                return new NumberField(name, asDouble(value), def.isTokenized(), def.isStored());
            }
            if (def instanceof PrimaryKeyField) {
                return new PrimaryKeyField(name, asLong(value), def.isTokenized(), def.isStored());
            }
        } catch (NumberFormatException e) {
            log.warn("字段 {} 的值“{}”转不成数字，跳过该字段", name, value);
            return null;
        }
        log.warn("认不出的字段定义类型 {}，跳过字段 {}", def.getClass().getSimpleName(), name);
        return null;
    }

    /** 数字或能解析成数字的字符串都接受 —— 数据源（如 CSV）可能一切都给字符串。 */
    private static double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value).trim());
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value).trim());
    }
}
