package hawk.cloud.recall;

import common.IndexFormatConfig;
import directory.MMapDirectory;
import hawk.recall.reader.DirectoryReader;
import hawk.recall.request.RequestCompiler;
import hawk.recall.request.Schema;
import hawk.recall.search.Searcher;
import hawk.segment.core.anlyzer.Analyzer;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 本节点**当前版本**的检索器 —— 开着 reader 的那一层。方案见 {@code CLOUD_RECALL_SEARCH_DESIGN.md} §6。
 *
 * <h2>它解决两件事</h2>
 * <ol>
 *   <li><b>缓存</b>：不能每次查询都 open。{@link MMapDirectory} + {@link hawk.recall.reader.DirectoryReader}
 *       的加载不便宜，而分片是 200 MB 级的。</li>
 *   <li><b>安全换版</b>：拉取任务装上新版本之后要换 reader，但换的那一刻**可能有查询正拿着旧的**。
 *       直接 close 会让正在跑的查询崩掉。</li>
 * </ol>
 *
 * <h2>换版怎么做到安全</h2>
 * 一把读写锁：
 * <ul>
 *   <li><b>查询</b>取**读锁**，在锁内把活干完（编译、检索、取文档）—— 快照只在读锁内有效，
 *       绝不能被带出去；</li>
 *   <li><b>换版</b>取**写锁**：写锁下开新的、关旧的。此时不可能有查询在用旧的。</li>
 * </ul>
 * 换版是分钟级一次，读锁竞争可以忽略。这是"原子切换"的**第二层**：
 * {@code IndexStore.install} 保证**目录**换得干净，这一层保证**已经打开的 reader** 不被抽走。
 *
 * <h2>没索引时抛 {@link NoIndexException}</h2>
 * 拉取模型下"注册着、/health UP、但还没拉到索引"是**常态**（每个新起的副本都要经历）。
 * 调用方必须把它转成 **503**，不能变成一个"空结果" —— 否则协调器会把它当成"这个分片没匹配"，
 * 悄悄少掉 1/分片数 的语料（设计 §7.1）。
 */
@Component
public class LocalSearcher {

    private static final Logger log = LoggerFactory.getLogger(LocalSearcher.class);

    /**
     * 一个版本的检索环境。**只在 {@link #query} 的读锁内有效**，不要往外带。
     *
     * @param version  版本目录名
     * @param searcher 检索器
     * @param compiler 编译器（背靠本类那份**共享分词器**，见 {@link #analyzer}）
     * @param pkOfDoc  分片局部 docID → 业务主键。见下面 {@link #pkOf} 的说明
     */
    public record Snapshot(String version, Searcher searcher, RequestCompiler compiler, long[] pkOfDoc) {

        /**
         * {@code docID → 业务主键}；越界或没映射到返回 {@code -1}。
         *
         * <p><b>为什么要有这个数组</b>：检索返回的是 {@code ScoreDoc(docId)}，而 docId 是
         * **分片局部的**；调用方要的是业务主键。{@code PkMapStore} 存的是
         * {@code Map<pk, docId>} —— **单向的**，从 docId 反查不回去。所以在换版时一次性反转成数组。
         *
         * <p>为什么不直接从存储字段里取主键：那要求召回端**知道主键字段叫什么**，
         * 而那是建索引请求里定的。那就成了一个隐藏契约，改一次名就静默取不到主键。
         */
        public long pkOf(int docId) {
            return (docId >= 0 && docId < pkOfDoc.length) ? pkOfDoc[docId] : -1L;
        }
    }

    /** 在读锁内跑的一段活。**不要把它拿到的 Snapshot 带出去。** */
    @FunctionalInterface
    public interface SearchAction<T> {
        T apply(Snapshot snapshot) throws Exception;
    }

    /** 与索引器保持一致的分词窗口。**改这里必须同改索引器**（`IndexService`）。 */
    private static final int ANALYZER_N = 1;

    private final LocalIndexStore local;

    /**
     * **全进程一份**的分词器，所有版本共用。
     *
     * <h2>为什么必须共享 —— 是"贵"，不是"线程安全"</h2>
     * 构造一个 {@link NShortestPathAnalyzer} 会把 {@code data/npathword.data}
     * （**5.87 MB、62 万行**）整个读进来灌成一个 HashSet。放在这里意味着**换版不再重读词典**
     * —— 版本是分钟级一换的，每次重读纯属浪费。
     *
     * <h2>共享它是安全的（但"安全"是查出来的，不是设计出来的）</h2>
     * 它的字段只有两处会被写，而且都在**构造函数**里：
     * <ul>
     *   <li>{@code NPathSet} —— 构造时加载的**只读词典**（之后只有 {@code contains}）；</li>
     *   <li>{@code n} —— 构造时赋值。</li>
     * </ul>
     * <b>2 参数路径 {@code anlyze(value, field)} 全程不写任何实例字段</b>（其余赋值都是局部变量），
     * 所以可以并发调用。实证：索引器本来就是这样用的 —— {@code DocWriter} 被
     * {@code indexerThreadNum} 个线程并发调同一个实例，一直正常。
     *
     * <h2>⚠️ 什么会打破它</h2>
     * 它还有一个 **public 的 3 参数重载 {@code anlyze(value, field, n)}，会写 {@code this.n}**。
     * <b>今天没有任何调用方</b>（{@code Analyzer} 接口只声明了 2 参数那个），所以现在是安全的 ——
     * 但哪天有人想按查询动态调 {@code n} 就会踩上，而且**失败形态是"切出不一样的词"，
     * 不抛异常、不报错**，极难查。
     *
     * <p>要根治得把 {@code n} 改成一路传参、再删掉那个重载（见 {@code CLOUD_RECALL_SEARCH_DESIGN.md} §6.2
     * 的方案 B）—— 那要动 {@code segment}，暂时没做。**在那之前，谁也別去调 3 参数那个。**
     */
    private final Analyzer analyzer = new NShortestPathAnalyzer(ANALYZER_N);

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /** 当前版本（读锁下读、写锁下换）。null = 还没装过任何索引。 */
    private volatile Snapshot current;

    public LocalSearcher(LocalIndexStore local) {
        this.local = local;
    }

    /**
     * 在当前版本的读锁下执行 {@code action}。
     *
     * @throws NoIndexException 本节点服务不了当前版本 —— 没装过任何版本，**或装上了但打不开** → 调用方转 503
     * @throws IllegalStateException 版本在反复变化（理论上不会发生，版本是分钟级的）
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

            // 没命中：要么还没开过，要么版本变了。松开读锁之后去写锁下换版。
            try {
                refresh(newest);
            } catch (IOException e) {
                // **打不开当前版本也算"本节点服务不了"** —— 报 503 而不是 500：
                // 该做的事是让协调器换一个副本重试，而 500 会让它以为"服务端出了毛病"。
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

    /** 本节点是不是已经有可检索的索引。给 /health 之类用。 */
    public boolean isReady() {
        return newestVersion() != null;
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

    /** 当前版本目录名（目录名最大那份）；没有 → null。 */
    private String newestVersion() {
        Path newest = local.store().newest();
        return newest == null ? null : newest.getFileName().toString();
    }

    /**
     * 换版：**先在锁外把新版本打开**，再进写锁做"换指针 + 关旧的"。
     *
     * <h2>为什么 open 必须在锁外</h2>
     * {@code open()} 不便宜 —— 向量侧要建 ONNX 会话，实测 **1.5~4.5 秒**。
     * 而写锁是**排他**的：把它放在写锁里，这期间**所有查询都堵着**（不是变慢，是干等）。
     * 那样{@link #warmUp}也只是把"谁的查询被堵"从第一个查询换成那段时间的每个查询，没有真正解决。
     *
     * <p>放到锁外之后：打开期间查询照常**从旧版本**服务（{@code current} 还是旧的），
     * 只有最后那一下指针替换是互斥的 —— 微秒级。
     *
     * <h2>代价：可能白开一份</h2>
     * 锁外打开意味着两个线程可能同时发现"版本变了"、各开一份。进锁后先到的那份获胜，
     * 后到的那份**在锁外关掉**（它从没被发布过，没有查询拿得到它，关它是安全的）。
     * 这个白开只发生在"换版那一瞬正好有查询进来"的窄窗口，代价是几秒 CPU，换的是查询不堵。
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
                // 写锁下不可能有查询在用旧的 —— close 是安全的
                old.searcher().close();
                log.info("检索器换版：{} -> {}", old.version(), version);
            } else {
                log.info("检索器就绪：{}", version);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private Snapshot open(String version) throws IOException {
        Path dir = local.store().shardDir().resolve(version);
        DirectoryReader reader = DirectoryReader.open(MMapDirectory.open(dir));
        try {
            Searcher searcher = new Searcher(reader, new IndexFormatConfig());
            // Schema 随版本重建（字段集合可能变），但**分词器是上面那份共享的** ——
            // 换版不该重读 5.87 MB 词典。
            RequestCompiler compiler = new RequestCompiler(analyzer, Schema.from(reader));
            return new Snapshot(version, searcher, compiler, reversePkMap(reader.getPkMap()));
        } catch (RuntimeException e) {
            reader.close();                                 // 半开着别漏
            throw e;
        }
    }

    /**
     * {@code Map<pk, docId>} → {@code long[docId] = pk}。
     *
     * <p>用数组不用 Map：docId 是紧凑的 0..maxDoc，数组按索引寻址，
     * 3 万篇只要 264 KB（Map 的装箱开销是它的十几倍）。
     */
    private static long[] reversePkMap(Map<Long, Integer> pkMap) {
        int max = -1;
        for (int docId : pkMap.values()) {
            if (docId > max) {
                max = docId;
            }
        }
        long[] byDoc = new long[max + 1];
        Arrays.fill(byDoc, -1L);
        pkMap.forEach((pk, docId) -> byDoc[docId] = pk);
        return byDoc;
    }
}
