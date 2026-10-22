package hawk.cloud.recall;

import hawk.vector.search.VectorSearcher;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 本节点**当前版本**的向量检索器 —— {@link LocalSearcher} 的向量侧对照物。
 * 方案见 {@code CLOUD_RECALL_SEARCH_DESIGN.md} §6（换版那套是一样的）。
 *
 * <h2>和文本侧的三处不同</h2>
 * <ol>
 *   <li><b>要装模型</b>：查询是文本，得先编码成向量。所以本节点必须有一份**与建索引时同一模型**的
 *       ONNX 目录 —— 这是运维上的硬耦合，见下。</li>
 *   <li><b>主键直接就有</b>：{@code VectorHit(uniqueId, score)} 给的就是业务主键，
 *       不像文本那边要从 docID 反查 pkMap。所以这里没有 {@code pkOfDoc} 那套。</li>
 *   <li><b>没有 Schema / 没有编译器</b>：查询不需要分词、不需要编译成 AST。</li>
 * </ol>
 *
 * <h2>⚠️ 模型：必须与建索引时那份一致</h2>
 * {@code meta.json} 里记了 {@code modelFingerprint}，{@link VectorSearcher#open} 会对它做校验
 * —— **对不上就抛异常**（这是好事：比静默用一个不同模型编码查询、然后检索出一堆不相干结果是
 * 完全两回事）。代价是：**召回节点上必须有索引器建索引时用的那个模型**，
 * 否则这个版本永远打不开、节点一直回 503。模型目录由 {@code hawk.cloud.recall.vector.model-dir} 配。
 *
 * <p>为什么不直接用 {@code meta.json} 里记的那个 {@code modelDir}：那是**索引器本机的绝对路径**，
 * 换一台机器多半不存在。所以这里显式配置，让部署自己决定放哪。
 *
 * <h2>换版安全性同文本侧</h2>
 * 读写锁：查询取读锁、换版取写锁。换版时关掉旧的，此时不可能有查询在用它。
 * 模型会话（ONNX）在 {@code open} 里建、随 {@code close} 释放 —— 所以**换版会重建一次会话**，
 * 这是它的固有代价（模型和索引版本是绑在一起打开的）。
 */
@Component
public class LocalVectorSearcher {

    private static final Logger log = LoggerFactory.getLogger(LocalVectorSearcher.class);

    /** 一个版本的向量检索环境。**只在 {@link #query} 的读锁内有效**，不要往外带。 */
    public record Snapshot(String version, VectorSearcher searcher) {
    }

    /** 在读锁内跑的一段活。**不要把它拿到的 Snapshot 带出去。** */
    @FunctionalInterface
    public interface SearchAction<T> {
        T apply(Snapshot snapshot) throws Exception;
    }

    private final LocalIndexStore local;

    /** 模型目录。**必须与索引器建索引时那份一致**（有指纹校验兜底）。 */
    @Value("${hawk.cloud.recall.vector.model-dir:model/bge-small-zh-v1.5}")
    private String modelDir;

    /**
     * 向量要不要载入堆。
     *
     * <p>默认 **false（磁盘驻留）**：{@code VectorSearcher} 的注释说得很清楚 ——
     * 3 个字段载入堆是 600 MB 量级，磁盘驻留每张图只要约 5 MB。
     * 召回节点是**常驻**的，堆应该留给并发查询，不是留给向量。
     */
    @Value("${hawk.cloud.recall.vector.cache-vectors:false}")
    private boolean cacheVectors;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /** 当前版本（读锁下读、写锁下换）。null = 还没装过任何索引。 */
    private volatile Snapshot current;

    public LocalVectorSearcher(LocalIndexStore local) {
        this.local = local;
    }

    /**
     * 在当前版本的读锁下执行 {@code action}。
     *
     * @throws NoIndexException 还没装过任何索引版本 → 调用方转 503
     */
    public <T> T query(SearchAction<T> action) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            String newest = newestVersion();
            if (newest == null) {
                throw new NoIndexException("本节点还没有安装任何索引版本（拉取任务还没跑，或索引目录是空的）");
            }

            lock.readLock().lock();
            try {
                Snapshot snapshot = current;
                if (snapshot != null && snapshot.version().equals(newest)) {
                    return action.apply(snapshot);
                }
            } finally {
                lock.readLock().unlock();
            }

            try {
                refresh(newest);
            } catch (IOException e) {
                // **打不开当前版本也算"本节点服务不了"** —— 报 503 而不是 500。
                // 走这条的典型是模型指纹不符（部署时模型放错了），也可能是索引文件损坏。
                // 两种情况该做的事是同一件：让协调器换一个副本重试。
                // 报 500 的话协调器会把它当成"服务端出了毛病"，而我们明确知道该怎么处置。
                throw new NoIndexException("当前索引版本打不开，本节点服务不了（" + newest + "）：" + e.getMessage());
            }
        }
        throw new IllegalStateException("索引版本在反复变化，放弃本次查询");
    }

    /**
     * **预热**：现在就把当前版本打开，而不是等第一个查询。
     *
     * <h2>为什么需要它</h2>
     * 打开一个版本的代价不小（mmap + 图/段加载，向量侧还要建 ONNX 会话 —— 实测 1.5 秒）。
     * 而这个代价原本落在**换版之后的第一个用户查询**上：那个查询会被拖慢，
     * 足以撞上协调器的分片超时（默认 3 秒），于是**那一片在这次查询里整片算 0 条**
     * —— 换一次版就漏一次。实测撞到过。
     *
     * <h2>为什么只是"再走一遍已有的路"</h2>
     * {@code query()} 的前半段本来就是"看快照、必要时换版"，所以这里直接复用：
     * 传一个不做事的 action，只为触发打开。**锁语义、异常语义全都跟真查询一模一样**，
     * 没有第二条需要维护的路径。
     *
     * <p>调用方（拉取任务）在**后台线程**上调它，而且**不该让它的失败影响安装成功** ——
     * 索引已经装好了，预热只是省第一个查询的时间。
     *
     * @throws NoIndexException 还没有任何版本（刚装完不会走到这儿）
     */
    public void warmUp() throws Exception {
        query(snapshot -> null);
    }

    @PreDestroy
    public void shutdown() {
        lock.writeLock().lock();
        try {
            Snapshot snapshot = current;
            current = null;
            if (snapshot != null) {
                snapshot.searcher().close();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ------------------------------------------------------------------

    private String newestVersion() {
        Path newest = local.store().newest();
        return newest == null ? null : newest.getFileName().toString();
    }

    /**
     * 换版：**先在锁外把新版本打开**，再进写锁做"换指针 + 关旧的"。
     *
     * <p>理由见 {@link LocalSearcher#refresh} —— 说白了：{@code open()} 在这里要建 ONNX 会话
     * （实测 1.5~4.5 秒），放进写锁会让这期间**所有查询都干等**。放锁外之后，
     * 打开期间查询照常从**旧版本**服务，只有指针替换那一下互斥（微秒级）。
     */
    /**
     * 换版：**在写锁里 open** —— 只有一个人开，其余人等它开完。
     *
     * <h2>为什么不能把 open 挪到锁外（试过，更糟）</h2>
     * 直觉上"锁外开、锁里只换指针"更好 —— 打开期间查询还能从旧版本服务。
     * <b>实测把它证伪了</b>：窗口期内**每个**查询都会发现"版本变了"，于是**各自开一份**
     * （不是"白开一份"），N 个并发 ONNX 会话互相抢 CPU，把 4.5 秒的开销拖成 **32 秒**。
     *
     * <p>放锁里则是：一个人开，其余人在锁上等同样的时间，然后一起拿到新版本。
     * **上界是"一次 open"，不是"N 次 open"** —— 慢但可预期，不会雪崩。
     *
     * <p>真正避免这个窗口靠 {@link #warmUp}：让打开发生在**没有查询在等**的时候
     * （拉取任务装完之后、以及进程启动时）。
     */
    private void refresh(String version) throws IOException {
        lock.writeLock().lock();
        try {
            Snapshot old = current;
            if (old != null && old.version().equals(version)) {
                return;                                     // 别人已经换好了
            }
            Snapshot fresh = open(version);
            current = fresh;
            if (old != null) {
                // 写锁下不可能有查询在用旧的 —— close 是安全的（它同时释放 ONNX 会话）
                old.searcher().close();
                log.info("向量检索器换版：{} -> {}", old.version(), version);
            } else {
                log.info("向量检索器就绪：{}（模型 {}）", version, modelDir);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private Snapshot open(String version) throws IOException {
        Path dir = local.store().shardDir().resolve(version);
        try {
            // 模型指纹对不上会在这里抛 IOException —— **让它抛**：宁可这个版本打不开、
            // 一直回 503，也不要拿一个不同的模型去编码查询、然后把一堆不相干结果当"正常"返回。
            VectorSearcher searcher = VectorSearcher.open(dir, Path.of(modelDir), cacheVectors);
            return new Snapshot(version, searcher);
        } catch (IOException e) {
            throw e;                                        // 已经是我们能表达的那一类
        } catch (Exception e) {
            // VectorSearcher.open 还会抛 OrtException（ONNX 原生层）。
            // 统一包成 IOException，别让原生层的异常类型漏到调用方那里去。
            throw new IOException("打开向量索引失败（" + version + "，模型 " + modelDir + "）："
                    + e.getMessage(), e);
        }
    }
}
