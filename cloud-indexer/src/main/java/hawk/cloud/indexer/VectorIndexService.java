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
 * 建完仍走既有的 {@code coordinatorNotifier.indexComplete} 通知协调器 ——
 * 调用方不需要轮询，协调器会收到回调。
 *
 * <h2>互斥锁</h2>
 * 本节点**同时只建一份向量索引**：{@link #running} 用 CAS 抢占，抢不到说明正在建，
 * 端点立刻回 {@code indexing} 而不是排队。理由是排队会让"什么时候轮到我"变得不可预测，
 * 而调用方（协调器）宁可立刻知道"这个节点忙着"，自己换个时间或换个副本再来。
 */
@Service
public class VectorIndexService {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexService.class);

    private final DataSource dataSource;
    private final CoordinatorNotifier coordinatorNotifier;
    private final IndexPusher indexPusher;

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
     * 推送阶段的"许可"。**用 Semaphore 而不是 ReentrantLock** —— 锁是**线程私有**的，
     * 而补推要"请求线程拿到、后台线程释放"，ReentrantLock 会抛 IllegalMonitorStateException。
     *
     * <p>作用见方案 §4.3：凡是"读已有 stamp 目录"或"删 stamp 目录"的操作都在它里面，
     * 所以**清理与（重）推送天然互斥**。
     */
    private final java.util.concurrent.Semaphore pushPermit = new java.util.concurrent.Semaphore(1);

    /** 补推的后台线程。排队在单线程上，天然串行。 */
    private final ExecutorService repushExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "text-repush");
        t.setDaemon(true);
        return t;
    });

    /**
     * 后台建索引的线程。**单线程**：{@link #running} 已经保证了同时只有一份在跑，
     * 多线程池只会让队列里堆着永远不会被并行执行的任务。
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vector-indexer");
        t.setDaemon(true);
        return t;
    });

    public VectorIndexService(
            DataSource dataSource,
            CoordinatorNotifier coordinatorNotifier,
            IndexPusher indexPusher,
            @Value("${hawk.cloud.indexer.data-source.batch-size:1000}") int batchSize,
            @Value("${hawk.cloud.indexer.shard-num}") String shardNum) {
        this.dataSource = dataSource;
        this.coordinatorNotifier = coordinatorNotifier;
        this.indexPusher = indexPusher;
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
                              String modelDir, String device, Long round) {
        if (!fullPermit.tryAcquire()) {
            return false;
        }
        try {
            executor.submit(() -> {
                try {
                    doIndex(shardCount, fieldList, vectorFields, modelDir, device, round);
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
                         String modelDir, String device, Long round) throws Exception {
        // ★ 短路：本节点已经有这一轮了，就别重算 —— 直接把那份重推一遍。
        // 向量这边收益最大：编码 10 万篇要 16 分钟，而"推送失败、构建成功"根本不需要重算。
        // 放在最前面：连主键字段都不用解析。
        if (round != null) {
            Path existing = findStampDirOfRound(round);
            if (existing != null) {
                log.info("round {} 本节点已有（{}），不重算，直接重推", round, existing.getFileName());
                pushToRecall(existing.getFileName().toString(), existing);
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

        // 时间戳生成一次：目录名、推送参数、通知里的 stamp 都用它（方案 §5.1）
        String stamp = IndexStamp.now();
        Path indexDirPath = IndexService.indexDirOf(indexDirRoot, role, shardNum, stamp);
        Files.createDirectories(indexDirPath);

        VectorIndexConfig config = new VectorIndexConfig(vectorFields)
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
        try (VectorIndexWriter writer = new VectorIndexWriter(config, indexDirPath)) {
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
            // 轮次写进索引目录。位置在 commit 之后、push 之前 —— 早于 commit 会把半成品标上轮次
            // （下一轮看到它就走短路、把半成品推出去），晚于 push 则打包时文件还不在。
            if (round != null) {
                IndexRound.write(indexDirPath, round);
            }
            log.info("向量索引完成：收下 {}，主键缺失 {}，字段全空 {}，用时 {}s -> {}",
                    writer.accepted(), writer.droppedNoKey(), writer.droppedNoField(),
                    (System.currentTimeMillis() - t0) / 1000, indexDirPath.toAbsolutePath());
        }

        // 放在最后、且在两段 try 之外：只有 commit 成功才会走到这里，不会发出"建好了"的假通知。
        // 通知本身尽力而为，失败只记日志（见 CoordinatorNotifier）。
        pushToRecall(stamp, indexDirPath);
    }

    /**
     * 推给本分片的召回节点，再按结果决定是否通知协调器。
     *
     * <p><b>取 Lock2 用阻塞 {@code lock()}</b> —— 已经 commit 了，不能因为"有人在重推"
     * 就半途而废。这个不对称是刻意的（方案 §4.3）。
     */
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
                IndexPusher.PushResult r = indexPusher.push("vector-recall", shardNum, stamp, "vector", dir, only);
                if (!r.allFailed()) {
                    // 推完走**同一条回报通路** —— 不为补推再造一个回报接口
                    coordinatorNotifier.indexComplete(role, "vector-recall", shardNum, stamp, r.pushed(), r.failed());
                }
            } catch (Throwable t) {
                log.error("补推失败（stamp={}）", stamp, t);
            } finally {
                pushPermit.release();                       // 后台线程释放 —— 所以必须是 Semaphore
            }
        });
        return IndexPusher.RepushOutcome.ok();
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

    /** 阻塞抢推送许可；被中断就中断线程并放行（照推是安全的，只是少了互斥）。 */
    private void acquirePermit() {
        try {
            pushPermit.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void pushToRecall(String stamp, Path indexDirPath) {
        acquirePermit();
        try {
            IndexPusher.PushResult r = indexPusher.push(
                    "vector-recall", shardNum, stamp, "vector", indexDirPath, null);
            if (r.allFailed()) {
                log.error("{} stamp={} 一个副本都没推成功，不通知协调器", shardNum, stamp);
            } else {
                coordinatorNotifier.indexComplete(role, "vector-recall", shardNum, stamp,
                        r.pushed(), r.failed());
            }
            IndexService.pruneOldStamps(indexDirPath.getParent());
        } finally {
            pushPermit.release();
        }
    }
}
