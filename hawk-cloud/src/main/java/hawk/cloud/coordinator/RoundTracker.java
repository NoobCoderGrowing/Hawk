package hawk.cloud.coordinator;

import com.alibaba.fastjson.JSONObject;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 让每一轮索引**收敛**：定时检查每个分片「有没有召回副本建成当前轮次」，没有就重下发。
 * 方案见 {@code DESIGN.md} §3.8。
 *
 * <h2>它是"状态收敛"（level-triggered），不是"事件驱动"</h2>
 * 判据是「**现在有没有**副本报当前轮次」，不是「有没有收到过通知」。于是下面这些失败模式
 * **全部塌缩成同一个分支**，不需要分别为它们写代码：
 * <ul>
 *   <li>索引器建到一半死了 / 编码抛异常 / 建完就被重启；</li>
 *   <li>索引器建完就重启了，登记跟着旧会话变成离线；</li>
 *   <li>当初就 409 没派出去；</li>
 *   <li><b>整个分片一个副本都没推成功</b> —— 这一条通知路径根本不会触发（{@code pushToRecall}
 *       在 {@code allFailed()} 时不通知），只有状态查询才发现得了。</li>
 * </ul>
 *
 * <h2>它管到哪一步为止</h2>
 * 本类只保证「**每个分片都有一份索引被建出来**」。索引建完之后**怎么到召回端**不归它管 ——
 * 那是召回端自己的定时拉取任务（{@code IndexPullJob}）的事，失败也由它自己下一轮重试。
 *
 * <p>这条分界是拉取模型带来的简化：以前协调器还要记"哪个副本没推成功"、催补推、
 * 记 attempts —— 那一整块**都不存在了**，因为"送达"由召回端自己的定时拉取负责，
 * 失败它自己下一轮重试。
 *
 * <h2>四条纪律（缺一条这个机制就会伤到自己）</h2>
 * <ol>
 *   <li><b>宽限期远大于一次构建耗时。</b>构建是分钟级，按 {@code NodeRegistry} 那个 60 秒的节奏查
 *       会在构建**刚开始**时就判未收敛 → 重下发 → 两个副本同时建同一片。这不是优化，是正确性。</li>
 *   <li><b>次数有上限，到顶放弃并告警</b>，并写"放弃标记"把派发闸门打开（否则永久坏的分片
 *       会让这个 role 再也建不了索引）。</li>
 *   <li><b>检查和重下发都在专用线程池上跑</b>，{@code @Scheduled} 只负责提交 ——
 *       {@code NodeRegistry.poll} 与别的定时任务共用那个 <b>单线程</b> {@code scheduling-1} 池，
 *       而一轮检查要读 N 个分片的注册中心，把注册中心轮询一起卡住就不好玩了。</li>
 *   <li><b>重下发前重读一次轮次</b>，和自己这次检查依据的不一样就跳过 —— 见 {@link #reDispatch}。</li>
 * </ol>
 */
@Service
public class RoundTracker {

    private static final Logger log = LoggerFactory.getLogger(RoundTracker.class);

    /** 四种 role 里，本类只关心这两类索引器。 */
    private static final List<String> INDEXER_ROLES =
            List.of(IndexBuildDispatcher.ROLE_TEXT, IndexBuildDispatcher.ROLE_VECTOR);

    private final RoundStore roundStore;
    private final ConvergenceChecker checker;
    private final IndexBuildDispatcher dispatcher;

    /**
     * 补推轮次上限。到顶就写放弃标记 + 告警（§7.6②）。
     *
     * <p>没有上限的话，一个**永久坏**的分片会被每 {@code round-check-interval-ms} 重建一轮，
     * **永远建下去**，把集群资源一直吃住。
     */
    @Value("${hawk.cloud.coordinator.round-max-attempts:3}")
    private int maxAttempts;

    /** 请求体丢了时的兜底分片数（正常路径用的是本轮派发记下来的那个）。 */
    @Value("${hawk.cloud.coordinator.shard-count:3}")
    private int defaultShardCount;

    /** role → 已催到第几轮。**只在轮次变化时归零** —— 否则"每轮失败→归零"会循环，永远到不了上限。 */
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    private record Attempts(long round, int count) {
    }

    /**
     * 干活的线程。**单线程**：一轮检查是串行的，而且本来也不该和下一轮重叠。
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "round-tracker");
        t.setDaemon(true);
        return t;
    });

    public RoundTracker(RoundStore roundStore, ConvergenceChecker checker,
                        IndexBuildDispatcher dispatcher) {
        this.roundStore = roundStore;
        this.checker = checker;
        this.dispatcher = dispatcher;
    }

    /**
     * 定时提交。**这里只提交，活在线程池里干** —— 见类注释纪律 3。
     *
     * <p>{@code initialDelay} 与间隔相同：服务刚起来时先把节点表拉一轮，别在
     * {@code NodeRegistry} 还是空的时候就判"未收敛"。
     */
    @Scheduled(fixedDelayString = "${hawk.cloud.coordinator.round-check-interval-ms:1800000}",
               initialDelayString = "${hawk.cloud.coordinator.round-check-interval-ms:1800000}")
    public void scheduleRound() {
        executor.submit(() -> {
            for (String role : INDEXER_ROLES) {
                try {
                    checkRole(role);
                } catch (Throwable t) {
                    // 一个 role 出错不该影响另一个
                    log.error("{} 的轮次检查失败", role, t);
                }
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    /** 给测试/监控单跑一次（同步）。 */
    public void checkNow() {
        for (String role : INDEXER_ROLES) {
            checkRole(role);
        }
    }

    /** 当前待补的轮次（监控用）。 */
    public Map<String, Attempts> pending() {
        return Map.copyOf(attempts);
    }

    // ------------------------------------------------------------------

    private void checkRole(String indexerRole) {
        // 纪律：只有拿到锁的协调器才可以 track（方案 §8.3）。
        // 没拿到**不是错误** —— 说明另一个协调器在负责，本节点什么都不做。
        if (!roundStore.tryAcquireTrackerLock(indexerRole)) {
            log.info("{} 的 tracker 锁不在本节点，本轮跳过", indexerRole);
            return;
        }

        IndexBuildDispatcher.Dispatched last = dispatcher.lastDispatch(indexerRole);
        long expected = roundStore.read(indexerRole);

        if (expected <= 0) {
            attempts.remove(indexerRole);       // 还从没派发过任何一轮
            return;
        }
        if (roundStore.abandonedRound(indexerRole) == expected) {
            // 这一轮已经放弃过了。**跳过** —— 不跳过的话 attempts 被清掉之后会从 0 重新数，
            // 于是每个上限周期都重新告警 + 重新写一遍放弃标记，永远刷下去。
            attempts.remove(indexerRole);
            return;
        }

        // 分片数**必须用本轮派发时定下的那个**，不能拿配置里的兜 —— 调用方传 1、配置是 3 的话，
        // 我们会去查根本不存在的 shard-1/shard-2（恒为"没有在线副本"），于是永远只告警、不重下发，
        // 真正出问题的 shard-0 被淹没在里面。实机上撞到过。
        int shardCount = roundStore.readShardCount(indexerRole);
        if (shardCount <= 0) {
            shardCount = last != null ? last.shardCount() : defaultShardCount;
        }

        List<ConvergenceChecker.ShardResult> results = new ArrayList<>();
        for (int i = 0; i < shardCount; i++) {
            results.add(checker.checkShard(indexerRole, "shard-" + i, expected));
        }

        List<ConvergenceChecker.ShardResult> bad = results.stream()
                .filter(r -> r.verdict() != ConvergenceChecker.Verdict.CONVERGED)
                .toList();

        if (bad.isEmpty()) {
            if (attempts.remove(indexerRole) != null) {
                log.info("{} 第 {} 轮已收敛（{} 个分片全部有副本报 {}）",
                        indexerRole, expected, shardCount, expected);
            }
            return;
        }

        // ★ 不变量：count 只在**轮次变化**时归零。同一个轮次的任何后续检查都不碰它 ——
        //   否则"每轮失败 → 归零 → 永远到不了上限"。
        // ★ 自增放在"发起一轮"这里，不放在"收到回报"时 —— 这样不依赖任何回报，
        //   回报丢了也照样计数、照样会放弃。
        Attempts a = attempts.compute(indexerRole, (k, old) ->
                (old == null || old.round() != expected) ? new Attempts(expected, 0) : old);

        if (a.count() >= maxAttempts) {
            abandon(indexerRole, expected, bad);
            return;
        }
        attempts.put(indexerRole, new Attempts(expected, a.count() + 1));

        log.warn("{} 第 {} 轮有 {} 个分片没收敛（已催 {}/{}）：{}",
                indexerRole, expected, bad.size(), a.count() + 1, maxAttempts, describe(bad));

        // ★ 只有 STALE 才值得重下发。
        //   AHEAD（有副本登记的轮次比我们还新）和 NO_REPLICA（这一片根本没有在线索引器副本）
        //   **重下发也修不好**：前者会把更新的索引覆盖回旧的，后者一个字节都发不出去。
        //   但它们**仍然计次** —— 计次的目的是"有界努力之后把闸门打开"，不是为了重下发。
        boolean worthRedispatch = bad.stream()
                .anyMatch(r -> r.verdict() == ConvergenceChecker.Verdict.STALE);

        if (!worthRedispatch) {
            log.error("{} 第 {} 轮没收敛，但这一轮**重下发也修不好**（分片都没有在线索引器副本，"
                            + "或有副本比我们还新），只告警不重下发；累计 {}/{}",
                    indexerRole, expected, a.count() + 1, maxAttempts);
            return;
        }

        // ④ 重下发前重读一次轮次：变了说明别人正在推进，这一轮已经被取代，重下发只会白烧一轮
        //    （而且它的 stamp 会更晚 —— 召回端的 newest() 反而指向旧轮次那份）。
        //    没变才动手 —— 比如原派发者已经死了，那正是接手的时候。
        long recheck = roundStore.read(indexerRole);
        if (recheck != expected) {
            log.info("{} 轮次已从 {} 前进到 {}，本次重下发作废", indexerRole, expected, recheck);
            return;
        }

        if (last == null) {
            // 协调器重启过：轮次在注册中心里还在，但**本轮的请求体在内存里，已经没了**。
            // 没有请求体就重下发不出构建（索引器会 400）。只能告警，等次数耗尽后放弃，
            // 由人工重新发起 —— 这是诚实的行为，不要假装能自愈。
            log.error("{} 第 {} 轮没收敛，但本节点没有这一轮的请求体（协调器重启过），无法重下发；"
                    + "累计 {}/{}", indexerRole, expected, a.count() + 1, maxAttempts);
            return;
        }

        reDispatch(indexerRole, expected, last);
    }

    /**
     * 重下发**同一轮**。走 {@link IndexBuildDispatcher#redispatch} —— 它**不推轮次、不过闸门**，
     * 这两点都是必须的（那里有说明，以及为什么会死锁）。
     */
    private void reDispatch(String indexerRole, long expected,
                            IndexBuildDispatcher.Dispatched last) {
        IndexBuildDispatcher.DispatchResult r = dispatcher.redispatch(
                indexerRole, expected, last.shardCount(), last.request());

        switch (r.status()) {
            case DISPATCHED, PARTIAL -> log.info("{} 第 {} 轮重下发：成功 {} 片，失败 {} 片",
                    indexerRole, expected, r.dispatched().size(), r.failed().size());
            case BAD_REQUEST ->
                    // 请求体被索引器拒了。**别再重试** —— 同样的体会一直 400，
                    // 催满上限除了刷日志没有任何用。
                    log.error("{} 第 {} 轮重下发的请求体被索引器拒绝（{}）",
                            indexerRole, expected, r.message());
            default -> log.warn("{} 第 {} 轮重下发未派出任何分片（{}）",
                    indexerRole, expected, r.message());
        }
    }

    /**
     * 催满上限，放弃这一轮。
     *
     * <p>写"放弃标记"是**必须的**：派发闸门靠它放行，否则一个永久坏的分片会让这个 role
     * 再也建不了索引。
     */
    private void abandon(String indexerRole, long round,
                         List<ConvergenceChecker.ShardResult> bad) {
        attempts.remove(indexerRole);
        try {
            roundStore.markAbandoned(indexerRole, round);
        } catch (RuntimeException e) {
            // 标记写不上去 = 闸门打不开。这条比"这一轮没收敛"更严重，要显眼。
            log.error("写 {} 的放弃标记失败，派发闸门可能打不开", indexerRole, e);
        }
        log.error("{} 第 {} 轮催满 {} 次仍有分片没收敛 —— 放弃，写放弃标记放行下一轮。"
                        + "这些分片要保持陈直到下一次重建：{}",
                indexerRole, round, maxAttempts, describe(bad));
    }

    private static String describe(List<ConvergenceChecker.ShardResult> bad) {
        return bad.stream()
                .map(r -> r.shardNum() + "=" + r.verdict() + "（" + r.detail() + "）")
                .toList().toString();
    }
}
