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
import java.util.concurrent.atomic.AtomicBoolean;

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
     * "本节点正在建向量索引"这个状态本身。用 CAS 抢 —— **不用锁对象排队**，
     * 抢不到就该让调用方立刻知道，而不是让它挂在那里等。
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

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
            @Value("${hawk.cloud.indexer.data-source.batch-size:1000}") int batchSize,
            @Value("${hawk.cloud.indexer.shard-num}") String shardNum) {
        this.dataSource = dataSource;
        this.coordinatorNotifier = coordinatorNotifier;
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
                              String modelDir, String device) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        try {
            executor.submit(() -> {
                try {
                    doIndex(shardCount, fieldList, vectorFields, modelDir, device);
                } catch (Throwable t) {
                    // 后台异常没人接，必须在这里落地 —— 否则这个任务失败得悄无声息
                    log.error("向量索引失败（shard-num={}，vectorFields={}）", shardNum, vectorFields, t);
                } finally {
                    running.set(false);   // 成功、失败都要放锁，否则这个节点再也不会接活
                }
            });
            return true;
        } catch (RuntimeException e) {
            // 提交本身失败（线程池已关）—— 锁必须还回去
            running.set(false);
            throw e;
        }
    }

    /** 本节点是不是正在建向量索引。给 {@code /health} 之类用。 */
    public boolean isRunning() {
        return running.get();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    // ------------------------------------------------------------------ 干活

    private void doIndex(int shardCount, List<Field> fieldList, List<String> vectorFields,
                         String modelDir, String device) throws Exception {
        int myShardIndex = IndexService.shardIndexOf(shardNum);

        String primaryKeyField = IndexService.primaryKeyFieldName(fieldList);
        if (primaryKeyField == null) {
            log.error("fieldList 里没有 PrimaryKeyField 定义，算不出文档归属哪个分片，向量索引中止。字段：{}",
                    fieldList.stream().map(Field::getName).toList());
            return;
        }

        Path indexDirPath = IndexService.indexDirOf(indexDirRoot, role, shardNum, LocalDate.now());
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
            writer.commit();
            log.info("向量索引完成：收下 {}，主键缺失 {}，字段全空 {}，用时 {}s -> {}",
                    writer.accepted(), writer.droppedNoKey(), writer.droppedNoField(),
                    (System.currentTimeMillis() - t0) / 1000, indexDirPath.toAbsolutePath());
        }

        // 放在最后、且在两段 try 之外：只有 commit 成功才会走到这里，不会发出"建好了"的假通知。
        // 通知本身尽力而为，失败只记日志（见 CoordinatorNotifier）。
        coordinatorNotifier.indexComplete(role, shardNum, indexDirPath.toAbsolutePath().toString());
    }
}
