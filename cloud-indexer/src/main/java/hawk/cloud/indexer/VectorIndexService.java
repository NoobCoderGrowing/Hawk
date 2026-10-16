package hawk.cloud.indexer;

import hawk.cloud.indexer.dataSource.DataSource;
import hawk.cloud.indexer.dataSource.OriDocument;
import field.Field;
import hawk.vector.model.OnnxDevice;
import hawk.vector.writer.VectorIndexWriter;
import hawk.vector.writer.config.VectorIndexConfig;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * 建**向量索引分片** —— 与 {@link IndexService} 同构，只是写的是向量索引而不是倒排。
 *
 * <h2>为什么是异步的</h2>
 * 编码慢得多。实测 CPU **202 条/秒**（maxLen=64），10 万篇 × 2 字段 ≈ **16 分钟**，
 * 而建倒排只要 ~8 秒。同步阻塞意味着那条 HTTP 连接要挂 16 分钟 ——
 * 过不了网关超时（nginx 默认 60 秒），超时后客户端重试还会**再建一遍**。
 *
 * <p>所以端点立刻返回，真正的活在 {@link #executor} 的后台线程里跑；
 * 建完把「我这儿有第 R 轮」登记到注册中心（{@link IndexReadyRegistry}），
 * **召回端下一轮自己会来拉** —— 索引器不需要知道有哪些召回节点。
 *
 * <h2>互斥锁</h2>
 * 本节点**同时只建一份向量索引**：{@link #fullPermit} 用 CAS 抢占，抢不到说明正在建，
 * 端点立刻回 {@code indexing} 而不是排队。理由是排队会让"什么时候轮到我"变得不可预测，
 * 而调用方（协调器）宁可立刻知道"这个节点忙着"，自己换个时间或换个副本再来。
 */
@Service
public class VectorIndexService {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexService.class);

    private final DataSource dataSource;

    /**
     * 建完之后登记「我这儿有第 R 轮」—— 召回端定时扫注册中心，看到就来拉。
     * 见 {@code INDEX_PULL_MODEL_DESIGN.md}。
     */
    private final IndexReadyRegistry readyRegistry;

    /** 每批多少条。与 {@link IndexService} 读同一个键 —— 算 offset 和传给数据源要用同一个值。 */
    private final int batchSize;

    /** 本节点的分片名，形如 {@code shard-0}。 */
    private final String shardNum;

    /** 本节点角色，应当配成 {@code vector-indexer}。进索引目录的第二段。 */
    @Value("${hawk.cloud.indexer.role}")
    private String role;

    @Value("${hawk.cloud.indexer.index-dir:cloud/index}")
    private String indexDirRoot;

    /** 默认模型目录；请求里可以带 {@code modelDir} 覆盖。 */
    @Value("${hawk.cloud.indexer.vector.model-dir:model/bge-small-zh-v1.5}")
    private String defaultModelDir;

    /** 默认设备：{@code auto} = 有 GPU 用 GPU、没有回退 CPU。请求里可覆盖。 */
    @Value("${hawk.cloud.indexer.vector.device:auto}")
    private String defaultDevice;

    @Value("${hawk.cloud.indexer.vector.m:16}")
    private int m;

    @Value("${hawk.cloud.indexer.vector.ef-construction:100}")
    private int efConstruction;

    /**
     * "本节点正在建向量索引"这个状态本身。{@code tryAcquire} 抢 —— **不排队**，
     * 抢不到就该让调用方立刻知道，而不是让它挂在那里等。
     *
     * <p><b>必须是 {@link Semaphore}，不能是 {@code ReentrantLock}。</b> 许可是**请求线程**
     * 抢的（{@link #startAsync} 的调用者），却由**后台线程**释放（{@link #executor} 里的
     * finally）—— {@code ReentrantLock} 线程私有，后台线程 unlock 会抛
     * {@code IllegalMonitorStateException}，而它发生在 {@code FutureTask} 里、没人 get，
     * 于是**被静默吞掉**：许可永远不还，节点从此对 {@code /vectorIndex} 恒回 409 且无任何异常日志。
     *
     * <p>与 {@link IndexService#startAsync} 那个许可互相独立，两条工作负载互不阻塞。
     */
    private final Semaphore fullPermit = new Semaphore(1);

    /**
     * 后台建索引的线程。**单线程**：{@link #fullPermit} 已经保证了同时只有一份在跑，
     * 多线程池只会让队列里堆着永远不会被并行执行的任务。
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vector-indexer");
        t.setDaemon(true);
        return t;
    });

    public VectorIndexService(
            DataSource dataSource,
            IndexReadyRegistry readyRegistry,
            @Value("${hawk.cloud.indexer.data-source.batch-size:1000}") int batchSize,
            @Value("${hawk.cloud.indexer.shard-num}") String shardNum) {
        this.dataSource = dataSource;
        this.readyRegistry = readyRegistry;
        this.batchSize = batchSize;
        this.shardNum = shardNum;
    }

    // ------------------------------------------------------------------ 对外

    /**
     * 尝试开始一次**异步**建索引。
     *
     * @return {@code false} = 本节点正在建索引（互斥锁没抢到），调用方应当立刻回 {@code indexing}；
     *         {@code true} = 已受理，活已经在后台跑
     */
    public boolean startAsync(int shardCount, List<Field> fieldList, List<String> vectorFields,
                              List<String> storedFields, String modelDir, String device, Long round) {
        if (!fullPermit.tryAcquire()) {
            return false;
        }
        try {
            executor.submit(() -> {
                try {
                    doIndex(shardCount, fieldList, vectorFields, storedFields, modelDir, device, round);
                } catch (Throwable t) {
                    // 后台异常没人接，必须在这里落地 —— 否则这个任务失败得悄无声息
                    log.error("向量索引失败（shard-num={}，vectorFields={}）", shardNum, vectorFields, t);
                } finally {
                    fullPermit.release();    // 成功、失败都要还，否则这个节点再也不会接活
                }
            });
            return true;
        } catch (RuntimeException e) {
            // 提交本身失败（线程池已关）—— 许可必须还回去
            fullPermit.release();
            throw e;
        }
    }

    /** 本节点是不是正在建向量索引。给 {@code /health} 之类用。 */
    public boolean isRunning() {
        return fullPermit.availablePermits() == 0;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    // ------------------------------------------------------------------ 干活

    private void doIndex(int shardCount, List<Field> fieldList, List<String> vectorFields,
                         List<String> storedFields, String modelDir, String device, Long round)
            throws Exception {
        // ★ 短路：本节点已经有这一轮了，就别重算 —— 直接把那份重推一遍。
        // 向量这边收益最大：编码 10 万篇要 16 分钟，而"推送失败、构建成功"根本不需要重算。
        // 放在最前面：连主键字段都不用解析。
        if (round != null) {
            Path existing = findStampDirOfRound(round);
            if (existing != null) {
                log.info("round {} 本节点已有（{}），不重算", round, existing.getFileName());
                // 重登记一次（幂等）—— 补上登记丢失的窗口。
                readyRegistry.advertise(round, existing.getFileName().toString());
                return;
            }
        }

        int myShardIndex = IndexService.shardIndexOf(shardNum);

        String primaryKeyField = IndexService.primaryKeyFieldName(fieldList);
        if (primaryKeyField == null) {
            log.error("fieldList 里没有 PrimaryKeyField 定义，算不出文档归属哪个分片，向量索引中止。字段：{}",
                    fieldList.stream().map(Field::getName).toList());
            return;
        }

        // 时间戳生成一次，再和轮次组成版本目录名。目录名 = v{零填充轮次}-{stamp}，
        // **排序由轮次决定，不由本机时钟决定** —— 见 IndexStamp 里那段说明。
        String stamp = IndexStamp.now();
        String version = IndexStamp.versionName(round == null ? 0L : round, stamp);
        Path indexDirPath = IndexService.indexDirOf(indexDirRoot, role, shardNum, version);
        // 先写临时目录，成功才改名 —— 否则中途失败会留下一个"最新的半成品"，
        // 召回端 newest() 正好选中它、于是永久 503。见 IndexService.tempBuildDirOf
        Path buildDir = IndexService.tempBuildDirOf(indexDirPath);
        IndexService.sweepStaleBuildDirs(indexDirPath.getParent(), 60 * 60 * 1000L);
        Files.createDirectories(buildDir);

        VectorIndexConfig config = new VectorIndexConfig(vectorFields)
                .setStoredFields(storedFields)
                .setModelDir(Path.of(modelDir != null ? modelDir : defaultModelDir))
                .setPrimaryKeyFieldName(primaryKeyField)
                .setM(m)
                .setEfConstruction(efConstruction)
                .setDevice(OnnxDevice.Kind.valueOf(
                        (device != null ? device : defaultDevice).toUpperCase(Locale.ROOT)));

        int batchCount = dataSource.getTotalBatchCount();
        log.info("开始建向量索引：shardCount={}，本节点分片下标={}（shard-num={}），batchSize={}，共 {} 批，"
                        + "主键字段={}，向量字段={}，模型={}，设备={}，输出目录={}",
                shardCount, myShardIndex, shardNum, batchSize, batchCount, primaryKeyField,
                vectorFields, config.getModelDir(), config.getDevice(), indexDirPath.toAbsolutePath());

        long t0 = System.currentTimeMillis();
        // built 在 try 之外声明，finally 才能看见它
        boolean built = false;
        try (VectorIndexWriter writer = new VectorIndexWriter(config, buildDir)) {
            for (int i = 0; i < batchCount; i++) {
                long offset = (long) i * batchSize;
                List<OriDocument> batch = dataSource.getBatchDocument(offset, batchSize, shardCount);
                if (batch == null) {
                    log.warn("第 {}/{} 批返回 null —— 契约规定永不返回 null，循环就此中断", i + 1, batchCount);
                    break;
                }
                if (batch.isEmpty()) {
                    log.info("第 {}/{} 批为空，视为取完，提前结束", i + 1, batchCount);
                    break;
                }
                for (OriDocument raw : batch) {
                    // 分片判定与倒排那边**同一套取模** —— 保证同一篇文档落在两个索引的同一个 shard-N 里
                    Long pk = IndexService.primaryKeyOf(raw, primaryKeyField);
                    if (pk == null || !IndexService.belongsToShard(pk, shardCount, myShardIndex)) {
                        continue;
                    }
                    writer.addDoc(IndexService.rebuildDocument(raw, fieldList));
                }
                log.info("第 {}/{} 批：offset={}，累计收下 {}",
                        i + 1, batchCount, offset, writer.accepted());
            }
            // 空分片：不建、不推、不通知（方案 §13.3）
            if (writer.accepted() == 0) {
                log.info("分片 {} 一条文档都没收下（空分片），不建索引、不推送、不通知", shardNum);
                return;
            }
            writer.commit();
            // 轮次写进**临时目录**。位置在 commit 之后、改名之前 ——
            // 早于 commit 会把半成品标上轮次（下一轮看到它就走短路、把半成品推出去）；
            // 晚于推送则打包时文件还不在。改名之后它自然跟着进正式目录。
            if (round != null) {
                IndexRound.write(buildDir, round);
            }
            // ★ 到这里这一份才真正完整 —— 原子改名，它从此才是"最新那个目录"
            Files.move(buildDir, indexDirPath, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            built = true;

            if (round != null) {
                // 改完名才登记 —— 登记的那一刻起召回端就可能来拉，拉的必须是已经改好名的那份
                readyRegistry.advertise(round, version);
            }
            log.info("向量索引完成：收下 {}，主键缺失 {}，字段全空 {}，用时 {}s -> {}",
                    writer.accepted(), writer.droppedNoKey(), writer.droppedNoField(),
                    (System.currentTimeMillis() - t0) / 1000, indexDirPath.toAbsolutePath());
        } finally {
            if (!built) {
                IndexService.deleteRecursively(buildDir);
            }
        }

        // 放在最后、且在两段 try 之外：只有 commit 成功才会走到这里，不会发出"建好了"的假通知。

        // 索引已经建好并登记完了，剩下的交给召回端自己来拉。这里只做本地保留策略。
        // prune 保留最新 2 份，而刚建好这一份就是最新的，所以不会把登记的那一份删掉。
        IndexService.pruneOldStamps(indexDirPath.getParent());
    }

    /** 给 {@link IndexDownloadController} 用：本节点「第 {@code round} 轮」的索引目录；没有返回 null。 */
    public Path indexDirOfRound(long round) {
        return findStampDirOfRound(round);
    }

    /**
     * 在本分片的索引目录下找「轮次等于 {@code round}」的那一份。
     *
     * <p>为什么可以直接扫目录：{@link IndexService#pruneOldStamps} 只留最新 2 份，所以这里最多看
     * 2 个目录，每次读一个几十字节的 {@code round} 文件。取**最新**的那个，与召回端
     * {@code newest()} 的判定口径一致。
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
                    .max(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        } catch (java.io.IOException e) {
            log.warn("扫 {} 找 round {} 失败：{}", shardDir, round, e.toString());
            return null;
        }
    }

}
