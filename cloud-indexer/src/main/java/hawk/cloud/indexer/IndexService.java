package hawk.cloud.indexer;

import hawk.cloud.indexer.dataSource.DataSource;
import hawk.cloud.indexer.dataSource.FileDataSource;
import hawk.cloud.indexer.dataSource.MySqlDataSource;
import hawk.cloud.indexer.dataSource.OriDocument;

import directory.MMapDirectory;
import document.Document;
import field.Field;
import field.FieldType;
import field.NumberField;
import field.PrimaryKeyField;
import field.StringField;
import hawk.indexer.writer.IndexWriter;
import hawk.indexer.writer.config.IndexConfig;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /** 建完把索引推给召回分片（方案 §3）。 */
    private final IndexPusher indexPusher;

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
     * "本节点正在建倒排索引"这个状态本身。
     *
     * <p><b>与 {@link VectorIndexService} 那个许可互相独立</b>，不共用 ——
     * 倒排和向量是两条独立的工作负载（一个吃分词器、一个吃 ONNX 编码），
     * 谁也挡不住谁才对。共用一个信号量会让"正在建向量索引"把 {@code /textIndex}
     * 也一并拒掉，那是错的。
     *
     * <p><b>必须是 {@link Semaphore}，不能是 {@code ReentrantLock}。</b>
     * 许可是**请求线程**抢的（也就是 {@link #startAsync} 的调用者），却由**后台线程**释放
     * （{@link #executor} 里那个 finally）—— 而 {@code ReentrantLock} 是**线程私有**的，
     * 后台线程 unlock 会抛 {@code IllegalMonitorStateException}。那个异常发生在
     * {@code FutureTask} 里、没人 get 它的结果，于是**被静默吞掉**：许可永远不还，
     * 这个节点从此对 {@code /textIndex} 恒回 409，而日志里一个异常都看不到。
     */
    private final Semaphore fullPermit = new Semaphore(1);

    /**
     * 推送阶段的"许可"。同样**不能是 {@code ReentrantLock}** —— 理由与 {@link #fullPermit}
     * 一模一样：补推是"请求线程拿到、后台线程释放"。
     *
     * <p>作用见方案 §4.3：凡是"读已有 stamp 目录"或"删 stamp 目录"的操作都在它里面，
     * 所以**清理与（重）推送天然互斥**。
     */
    private final Semaphore pushPermit = new Semaphore(1);

    /** 补推的后台线程。排队在单线程上，天然串行。 */
    private final ExecutorService repushExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "text-repush");
        t.setDaemon(true);
        return t;
    });

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
     * 数据源由 {@link DataSourceConfig} 按 {@code data-source.*} 配好后注入。
     *
     * <p>之所以收成一个 bean 而不是各自构造：{@code VectorIndexService} 也要用同一个源，
     * 两边各建一份会拿到两个实例（MySQL 那种还会各开一套连接）。
     */
    public IndexService(
            CoordinatorNotifier coordinatorNotifier,
            DataSource dataSource,
            IndexPusher indexPusher,
            @Value("${hawk.cloud.indexer.data-source.batch-size:1000}") int batchSize,
            @Value("${hawk.cloud.indexer.shard-num}") String shardNum) {
        this.batchSize = batchSize;
        this.shardNum = shardNum;
        this.coordinatorNotifier = coordinatorNotifier;
        this.dataSource = dataSource;
        this.indexPusher = indexPusher;
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
     * 后台建索引的线程。**单线程**：{@link #running} 已经保证同时只有一份在跑。
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "text-indexer");
        t.setDaemon(true);
        return t;
    });

    /**
     * 尝试开始一次**异步**建索引。
     *
     * @param round 协调器给的索引轮次；**直接调 {@code /textIndex} 旁路调用时为 {@code null}**。
     *              非 null 时会写进索引目录（见 {@link IndexRound}），并且**若本节点已有这一轮就直接重推**
     * @return {@code false} = 本节点正在建倒排索引（互斥锁没抢到），调用方应当立刻回
     *         {@code text-indexing}；{@code true} = 已受理，活已经在后台跑
     */
    public boolean startAsync(int shardCount, List<Field> fieldList, Long round) {
        // tryAcquire 不排队：抢不到立刻回 409，让协调器自己决定怎么办
        if (!fullPermit.tryAcquire()) {
            return false;
        }
        try {
            executor.submit(() -> {
                try {
                    index(shardCount, fieldList, round);
                } catch (Throwable t) {
                    // 后台异常没人接，必须在这里落地 —— 否则这个任务失败得悄无声息
                    log.error("倒排索引失败（shard-num={}）", shardNum, t);
                } finally {
                    fullPermit.release();   // 成功、失败都要还，否则这个节点再也不会接活
                }
            });
            return true;
        } catch (RuntimeException e) {
            fullPermit.release();           // 提交本身失败（线程池已关）—— 许可必须还回去
            throw e;
        }
    }

    /** 本节点是不是正在建倒排索引。 */
    public boolean isRunning() {
        return fullPermit.availablePermits() == 0;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    /**
     * 按批拉原始文档，挑出属于本分片的，装成文档并写进索引。
     *
     * <p><b>只能从 {@link #startAsync} 进来</b> —— 互斥锁在那一层，绕过去就等于绕过了互斥。
     *
     * @param shardCount 总分片数
     * @param fieldList  要索引/存储的字段定义，决定每个字段是否分词、是否存储
     * @param round      索引轮次；可为 {@code null}（旁路调用）
     */
    private void index(int shardCount, List<Field> fieldList, Long round) throws Exception {
        // ★ 短路：本节点已经有这一轮了，就别重算 —— 直接把那份重推一遍。
        // 这条把「推送失败、构建成功」这个**最常见的失败形态**从"重算 16 分钟"压到"重传一次"：
        // 网络抖动、召回节点当时不在线，都不需要重算，索引目录好好躺在盘上。
        // 放在最前面：连主键字段都不用解析，因为它跟"重推哪一份"无关。
        if (round != null) {
            Path existing = findStampDirOfRound(round);
            if (existing != null) {
                log.info("round {} 本节点已有（{}），不重算，直接重推", round, existing.getFileName());
                pushToRecall(existing.getFileName().toString(), existing);
                return;
            }
        }

        int myShardIndex = shardIndexOf(shardNum);

        String primaryKeyField = primaryKeyFieldName(fieldList);
        if (primaryKeyField == null) {
            // 没有主键就算不出文档归哪个分片，后续每一步都做不了 —— 与其每条文档刷一条警告，不如直接停。
            log.error("fieldList 里没有 PrimaryKeyField 定义，无法判断文档归属哪个分片，索引中止。字段：{}",
                    fieldList.stream().map(Field::getName).toList());
            return;
        }

        // 时间戳**生成一次**：本地目录名、推送参数、通知里的 stamp 都用它（方案 §5.1）
        String stamp = IndexStamp.now();
        int batchCount = dataSource.getTotalBatchCount();
        Path indexDirPath = indexDirOf(indexDirRoot, role, shardNum, stamp);
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

            // 空分片：不建索引、不推送、不通知（方案 §13.3）。
            // 不跳过的话 commit() 会抛"没有任何可索引的文档"，而且一份空索引推上去
            // 会被召回端当"最新"，把之前的有效索引顶掉
            if (accepted == 0) {
                log.info("分片 {} 一条文档都没收下（空分片），不建索引、不推送、不通知", shardNum);
                return;
            }
            writer.commit();
        } finally {
            // IndexWriter 没有 close()，但它构造时起了一个 ThreadPoolExecutor（线程数是
            // indexerThreadNum）。不关掉的话每个请求都漏一组线程 —— BuildIndex 是一次性进程所以
            // 从没暴露过这个问题。
            writer.getThreadPoolExecutor().shutdown();
        }

        // 轮次写进索引目录。位置在 commit 之后、push 之前：
        //   · commit 之前写 → commit 抛异常时目录里留了个孤零零的 round 文件，而索引是半成品，
        //     下一轮看到它还会走短路、把半成品推出去；
        //   · push 之后写 → 打包的时候文件还不在，推过去的那份没有轮次，tracker 永远判未收敛。
        // 所以只有这一个位置是对的。
        if (round != null) {
            IndexRound.write(indexDirPath, round);
        }

        log.info("索引结束：扫过 {} 条，写入 {} 条，非本分片 {} 条，无主键 {} 条 -> {}",
                scanned, accepted, otherShard, noPrimaryKey, indexDirPath.toAbsolutePath());

        // 走到这里说明 commit 已经成功 —— 通知协调器"这个 role 的这个分片建好了、路径在哪"。
        // 位置放在 finally 之后：commit 抛异常时不会执行到这里，也就不会发出成功的通知。
        // 通知本身尽力而为，失败只记日志（见 CoordinatorNotifier）。
        pushToRecall(stamp, indexDirPath);
    }

    /**
     * 在本分片的索引目录下找「轮次等于 {@code round}」的那一份。
     *
     * <p>为什么可以直接扫目录：{@link #pruneOldStamps} 只留最新 2 份，所以这里最多看 2 个目录，
     * 每次读一个几十字节的 {@code round} 文件。取**最新**的那个（同名时字典序最大），
     * 与召回端 {@code newest()} 的判定口径一致。
     *
     * @return 找到的目录；没有 → {@code null}
     */
    private Path findStampDirOfRound(long round) {
        Path shardDir = Path.of(indexDirRoot, role, shardNum);
        if (!Files.isDirectory(shardDir)) {
            return null;
        }
        try (var dirs = Files.list(shardDir)) {
            return dirs.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> Long.valueOf(round).equals(IndexRound.read(p)))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        } catch (IOException e) {
            log.warn("扫 {} 找 round {} 失败：{}", shardDir, round, e.toString());
            return null;
        }
    }

    /**
     * 推给本分片的召回节点，然后按结果决定是否通知协调器（方案 §4.3 / §6.2）。
     *
     * <p><b>取 Lock2 用阻塞 {@code lock()}，不是 tryLock</b> —— 走到这里索引已经 commit 了，
     * 不能因为"有人在重推"就半途而废（索引建好了却推不出去）。等几秒是唯一正确的选择。
     * 这个不对称是刻意的，别顺手统一掉。
     */
    private void pushToRecall(String stamp, Path indexDirPath) {
        acquirePermit();
        try {
            IndexPusher.PushResult r = indexPusher.push(
                    "text-recall", shardNum, stamp, "text", indexDirPath, null);
            if (r.allFailed()) {
                // 一个副本都没成功 —— 不通知。协调器会认为这个分片缺失，这是对的
                log.error("{} stamp={} 一个副本都没推成功，不通知协调器", shardNum, stamp);
            } else {
                coordinatorNotifier.indexComplete(role, "text-recall", shardNum, stamp,
                        r.pushed(), r.failed());
            }
            pruneOldStamps(indexDirPath.getParent());
        } finally {
            pushPermit.release();
        }
    }

    /** 索引端也留最新 2 份（方案 §5.6）—— 留 2 份意味着上一次的还能重推。 */

    /**
     * 补推（方案 §7.2）：**不建索引**，只拿 Lock2 重推一个已经存在的 stamp。
     *
     * <p>与 {@link #pushToRecall} 的区别：那个在 Lock1 里跑（构建流程的一部分），
     * 这个只拿 Lock2 —— 所以它能在**构建的编码阶段**旁边跑，不必等一个几分钟的构建。
     */
    public IndexPusher.RepushOutcome repush(String stamp, java.util.List<String> only) {
        // 抢不到许可：本节点正在推送/清理 —— 协调器下一轮再来
        if (!pushPermit.tryAcquire()) {
            return IndexPusher.RepushOutcome.busy();
        }
        Path dir = IndexService.indexDirOf(indexDirRoot, role, shardNum, stamp);
        if (!Files.isDirectory(dir)) {
            pushPermit.release();                           // 许可是抢来的，先还回去
            return IndexPusher.RepushOutcome.gone();        // 被保留策略清掉了
        }
        // **立刻返回，推在后台** —— 同步会把协调器唯一的调度线程占住，
        // 而注册中心轮询也跑在同一个池里（方案 §7.2 / §4.3）
        repushExecutor.submit(() -> {
            try {
                IndexPusher.PushResult r = indexPusher.push("text-recall", shardNum, stamp, "text", dir, only);
                if (!r.allFailed()) {
                    // 推完走**同一条回报通路** —— 不为补推再造一个回报接口
                    coordinatorNotifier.indexComplete(role, "text-recall", shardNum, stamp, r.pushed(), r.failed());
                }
            } catch (Throwable t) {
                log.error("补推失败（stamp={}）", stamp, t);
            } finally {
                pushPermit.release();                       // 后台线程释放 —— 所以必须是 Semaphore
            }
        });
        return IndexPusher.RepushOutcome.ok();
    }

    /** 阻塞抢推送许可；被中断就中断线程并放行（照推是安全的，只是少了互斥）。 */
    private void acquirePermit() {
        try {
            pushPermit.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void pruneOldStamps(Path shardDir) {
        try (java.util.stream.Stream<Path> s = Files.list(shardDir)) {
            List<Path> dirs = s.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
            for (Path p : dirs.subList(Math.min(2, dirs.size()), dirs.size())) {
                log.info("索引端保留最新 2 份，删除 {}", p.getFileName());
                try (java.util.stream.Stream<Path> w = Files.walk(p)) {
                    w.sorted(Comparator.reverseOrder()).forEach(x -> {
                        try { Files.deleteIfExists(x); } catch (IOException ignored) { }
                    });
                }
            }
        } catch (IOException e) {
            log.warn("清理旧索引目录失败：{}", e.toString());
        }
    }

    /**
     * 索引目录 = {@code 根目录 / 角色 / 分片名 / 当日日期}，如
     * {@code cloud/index/text-indexer/shard-0/2026-10-13}。
     *
     * <p>角色在最外层（紧跟根目录）：同一个节点上四类索引器各写各的目录树，互不干涉；
     * 再往下按分片、按日期分 —— 本节点只写自己那份，也不会把不同天的索引混在一起。
     * 时间戳格式见 {@link IndexStamp} —— 固定宽度，字典序即时间序。
     *
     * <p>静态以便单测（日期从参数进来，不在这里调 {@code now()}）。
     */
    static Path indexDirOf(String root, String role, String shardNum, String stamp) {
        return Path.of(root, role, shardNum, stamp);
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
            // 类型分发交给 FieldType —— 加新字段类型只改那一个枚举
            return FieldType.of(def).fromRawValue(name, value, def.isTokenized(), def.isStored());
        } catch (NumberFormatException e) {
            // NumberFormatException 是 IllegalArgumentException 的子类，必须放前面
            log.warn("字段 {} 的值“{}”转不成数字，跳过该字段", name, value);
            return null;
        } catch (IllegalArgumentException e) {
            log.warn("{}，跳过字段 {}", e.getMessage(), name);
            return null;
        }
    }
}
