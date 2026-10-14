package hawk.cloud.coordinator;

import io.github.noobcodergrowing.jregistryclient.CasResult;
import io.github.noobcodergrowing.jregistryclient.JRegistryClient;
import io.github.noobcodergrowing.jregistrycore.KV;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 索引轮次（{@code round}）的存放处 —— 一个从 0 开始、**全局唯一**的计数器，落在 JRegistry 里。
 * 方案见 {@code INDEX_BUILD_DISPATCH_DESIGN.md} §7.4。
 *
 * <h2>为什么要放进注册中心</h2>
 * 放内存里会在**协调器重启**时归零，于是所有分片看起来都"没有 round 0" → 整个集群全量重建一遍。
 * 注册中心是 Raft 复制的持久化存储，读回来就是原值，多协调器读到的也是同一个数。
 *
 * <h2>叶子路径：按 role 分开，且不挂 cloud/coordinator 下面</h2>
 * <pre>
 *   cloud/index-round/text-indexer
 *   cloud/index-round/vector-indexer
 * </pre>
 * <b>① 不能用一个全局计数器。</b>文本构建推到 7、向量构建再推到 8，此时 {@code text-recall}
 * 的各分片报的还是 7 → 文本侧被判"未收敛" → 白重建一轮。两个 role 是两条独立的工作负载。
 *
 * <p><b>② 不能挂在 {@code cloud/coordinator/} 底下。</b> 协调器的节点登记就挂在那棵树下面，
 * 而 {@code listAll} 是**递归**的 —— 子键会混进 {@code cloud/coordinator} 的列举结果里。
 * 用兄弟路径，别赌排序。
 *
 * <h2>自增靠 CAS，不靠读-改-写</h2>
 * 注册中心<b>没有原子自增</b>，{@code get → +1 → set} 是两个协调器会撞的读-改-写。
 * JRegistryClient 1.0.6 起有 {@code cas}（条件写，前值与期望一致才写），
 * 比较发生在状态机的 apply 阶段、所有副本按同一日志顺序看到同一前值 —— 所以是**原子**的：
 * 并发调用里只有一个能拿到 {@link CasResult#APPLIED}。
 */
@Service
public class RoundStore {

    private static final Logger log = LoggerFactory.getLogger(RoundStore.class);

    /** 轮次在注册中心里的根路径。**兄弟路径**，见类注释②。 */
    private static final String ROOT = "cloud/index-round";

    /** tracker 分布式锁的根路径。同样是兄弟路径。 */
    private static final String LOCK_ROOT = "cloud/index-track-lock";

    /** 「这一轮已放弃」的根路径。见 {@link #markAbandoned}。 */
    private static final String ABANDONED_ROOT = "cloud/index-round-abandoned";

    /** 「本轮派发了几个分片」的根路径。见 {@link #writeShardCount}。 */
    private static final String SHARDS_ROOT = "cloud/index-shards";

    /** 值就是轮次的十进制文本，不是 JSON。 */
    private static final String VALUE_TYPE = "string";

    /**
     * CAS 重试上限。CAS 的期望值来自一次**可能过时**的读，所以失败是正常的（别人抢先了），
     * 重读重试即可。真实竞争很轻（一轮构建是分钟级），5 次足够；到顶说明注册中心有问题，该报错。
     */
    private static final int MAX_CAS_RETRIES = 5;

    private final NodeRegistry nodeRegistry;

    public RoundStore(NodeRegistry nodeRegistry) {
        this.nodeRegistry = nodeRegistry;
    }

    /** {@code cloud/index-round/{indexerRole}}。静态以便单测。 */
    public static String keyOf(String indexerRole) {
        return ROOT + "/" + indexerRole;
    }

    /**
     * 读当前轮次。
     *
     * <p><b>key 不存在 → 0。</b>
     *
     * <p>⚠️ <b>注意这里把「key 不存在」和「读超时」混为一谈了</b> —— {@code JRegistryClient.getWithStatus}
     * 对这两种情况返回的都是 {@code null}，客户端**区分不了**。所以：
     * <ul>
     *   <li><b>后面紧跟一次 CAS 时是安全的</b>（{@link #startNewRound}）：读错了不要紧，
     *       CAS 会在服务端按**当前真实值**重新判定，错的读不会变成错的写，最多多试一轮。</li>
     *   <li><b>只读用途（比如 tracker 拿它当判据）不安全</b>：读到 0 而真实是 5，就会去查错误的轮次。
     *       那种场景需要另加一个"读不到就报错"的严格版，见方案 §10.5。</li>
     * </ul>
     *
     * @throws IllegalStateException 注册中心没连上（不是"读不到"，是"根本没通路"）
     */
    public long read(String indexerRole) {
        // 用 getWithStatus（返回 KV）而不是 get（返回 Pair）：本类只关心值，
        // 而 NodeRegistry 里读的也是 KV，保持一致。注意它 null 的两种含义是分不开的（见上）。
        KV kv = client().getWithStatus(keyOf(indexerRole));
        if (kv == null || kv.getData() == null) {
            return 0;
        }
        String text = new String(kv.getData(), StandardCharsets.UTF_8).trim();
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            // 这个 key 只有本类会写。读到别的东西说明有人手写了、或版本不兼容 —— 别猜，报错。
            throw new IllegalStateException("轮次 key 的值不是数字：“" + text + "”（" + keyOf(indexerRole) + "）");
        }
    }

    /**
     * 开一轮新的：{@code read → CAS(期望 n → n+1)}。返回**属于本次调用**的那个轮次。
     *
     * <p>调用方拿到它之后要**先派发再返回** —— 这是方案 §7.4 纪律 1：
     * 先写轮次再派发。反过来（派发成功后才写）有个静默吞失败的分支：派发整个失败时轮次没被推上去，
     * tracker 一看各副本报的还是上一轮的 R-1、注册中心里也是 R-1 → 判"已收敛" → 这次失败从此没人知道。
     *
     * @throws IllegalStateException 重试 {@link #MAX_CAS_RETRIES} 次仍未成功，或注册中心不可用
     */
    public long startNewRound(String indexerRole) {
        String key = keyOf(indexerRole);
        JRegistryClient client = client();

        for (int attempt = 1; attempt <= MAX_CAS_RETRIES; attempt++) {
            long current = read(indexerRole);
            long next = current + 1;
            // current == 0 意味着"这个 key 还不存在"（我们从不写 0），所以期望值是 null：
            // cas 收到 null 会置 casExpectAbsent，语义正是"期望不存在"（创建即抢锁）。
            byte[] expected = current == 0 ? null : bytes(current);

            CasResult result = client.cas(key, expected, bytes(next), VALUE_TYPE);
            switch (result) {
                case APPLIED -> {
                    log.info("{} 轮次推进：{} -> {}", indexerRole, current, next);
                    return next;
                }
                case NOT_APPLIED ->
                    // 别人抢先了，我们手里那个 current 已经过期。重读重试即可 —— 不是错误。
                    log.info("{} 轮次 CAS 未生效（第 {}/{} 次）：前值已被别人改掉，重读后再试",
                            indexerRole, attempt, MAX_CAS_RETRIES);
                case UNKNOWN ->
                    // 超时，写没写进去**不知道**。重读一次就能看出来（读到自己那个数就说明写进去了），
                    // 而重读之后重试本来就是这个循环在做的事 —— 所以按方案 §10.3 的说法：重试是安全的。
                    log.warn("{} 轮次 CAS 未收到答复（第 {}/{} 次）：写入与否未知，重读后再试",
                            indexerRole, attempt, MAX_CAS_RETRIES);
            }
        }
        throw new IllegalStateException(
                indexerRole + " 轮次在 " + MAX_CAS_RETRIES + " 次 CAS 内都没推进成功，请查注册中心");
    }

    // ------------------------------------------------------------------

    // ------------------------------------------------------------------ tracker 分布式锁
    //
    // 「只有拿到锁的协调器才可以去 track」（方案 §8.3）。锁靠注册中心**已有的**租约语义实现：
    // KV#isOffline 说的是「set 这个 key 的那个客户端是否还活着」（心跳超时/断连/其 owner 节点失效
    // 都会把它置位），所以持锁者一旦死掉，它的 key 会自己变成 offline —— **不需要额外的过期时间**。
    //
    // 唯一需要小心的是**不能抢活人的锁**：竞争者在覆盖别人的值之前，必须先确认对方 offline。
    // 没有这道判断的话，后写的那个总能覆盖先写的，锁就退化成"最后写的人赢"。

    /** 本进程的身份。每次启动换一个 —— 与 isOffline 的"会话"语义一致（重启就是新的会话）。 */
    private final String holderId = java.util.UUID.randomUUID().toString();

    /** 锁 key：{@code cloud/index-track-lock/{indexerRole}}。 */
    public static String lockKeyOf(String indexerRole) {
        return LOCK_ROOT + "/" + indexerRole;
    }

    /**
     * 尝试成为本 role 的 tracker。**已经持有就返回 true**（相当于续约 —— 连接本身就是在续约）。
     *
     * @return {@code true} = 锁在本节点手里，可以 track；{@code false} = 别的协调器在线持有，
     *         或注册中心不给答复（{@code UNKNOWN} 一律当没拿到 —— 宁可这一轮不 track，也不双 track）
     */
    public boolean tryAcquireTrackerLock(String indexerRole) {
        String key = lockKeyOf(indexerRole);
        JRegistryClient client = client();
        byte[] mine = holderId.getBytes(StandardCharsets.UTF_8);

        KV kv = client.getWithStatus(key);
        if (kv == null) {
            // 没人持有（key 不存在，或者读超时 —— 读超时时 CAS 也会因为期望不符而失败，是安全的）
            return client.cas(key, null, mine, VALUE_TYPE) == CasResult.APPLIED;
        }
        if (java.util.Arrays.equals(kv.getData(), mine)) {
            if (!kv.isOffline()) {
                return true;                    // 就是我的，且我还是活的
            }
            // 值是我的、但会话被判死了（网络抖动 → 重连 → 新旧会话换过）。抢回来。
            return client.cas(key, kv.getData(), mine, VALUE_TYPE) == CasResult.APPLIED;
        }
        if (kv.isOffline()) {
            // 前任已死（它的会话被注册中心标了 offline）→ 接管。这是**唯一**允许覆盖别人的情形。
            log.info("{} 的前任 tracker 已离线，本节点接管", indexerRole);
            return client.cas(key, kv.getData(), mine, VALUE_TYPE) == CasResult.APPLIED;
        }
        return false;                           // 别人在线持有 —— 不碰
    }

    // ------------------------------------------------------------------ 放弃标记

    /**
     * 记下「第 {@code round} 轮已经放弃」。
     *
     * <h2>为什么必须有这个东西</h2>
     * 派发前有一道闸：**当前轮次没收敛就不允许开下一轮**（方案 §7.5 那条的落地）。
     * 而没有这个标记的话，一个**永久坏**的分片（数据源里一条都不属于它 / 磁盘满 / 配置错）
     * 会让当前轮次永远不收敛 → **这个 role 从此再也建不了索引**。那不是"保守"，那是死锁。
     *
     * <p>所以 tracker 催满上限时会写这个标记，闸门看到它就把这一轮当没发生过。
     *
     * <p>写它的是 tracker，而 tracker 只可能有一个（锁保证了），所以这里用不着 CAS ——
     * 一个写者不需要条件写。
     */
    public void markAbandoned(String indexerRole, long round) {
        client().set(abandonedKeyOf(indexerRole),
                String.valueOf(round).getBytes(StandardCharsets.UTF_8), VALUE_TYPE);
    }

    /** 已经被放弃的那个轮次；没有 → 0。 */
    public long abandonedRound(String indexerRole) {
        KV kv = client().getWithStatus(abandonedKeyOf(indexerRole));
        if (kv == null || kv.getData() == null) {
            return 0;
        }
        try {
            return Long.parseLong(new String(kv.getData(), StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException e) {
            log.warn("放弃标记的值不是数字，当作没有：{}", new String(kv.getData(), StandardCharsets.UTF_8));
            return 0;
        }
    }

    private static String abandonedKeyOf(String indexerRole) {
        return ABANDONED_ROOT + "/" + indexerRole;
    }

    // ------------------------------------------------------------------ 本轮的分片数

    /**
     * 记下「本轮派发了几个分片」。
     *
     * <h2>为什么这个数也要存</h2>
     * round tracker 和派发闸门都要**按分片**做判断（"每一片有没有人建成"），所以它们必须知道
     * 本轮到底有几个分片。而请求体里的 {@code shardCount} 是**调用方给的**，可以不是配置里那个 ——
     * 用配置去兜的话，调用方传 1、配置是 3，tracker 就会去查根本不存在的 {@code shard-1}/{@code shard-2}，
     * 那两个恒为"没有在线副本"，于是它**永远只告警、永远不重下发**，而真正出问题的 {@code shard-0}
     * 被淹没在里面。实机上撞到过这个。
     *
     * <p>写它的只有派发那一方（外部入口在 {@link IndexBuildDispatcher#dispatch} 里，
     * 而且有 {@code dispatchPermit} 串行化），所以不需要 CAS。
     */
    public void writeShardCount(String indexerRole, int shardCount) {
        client().set(shardsKeyOf(indexerRole),
                String.valueOf(shardCount).getBytes(StandardCharsets.UTF_8), VALUE_TYPE);
    }

    /**
     * 本轮派发用的分片数。
     *
     * @return 分片数；**没有记录（从没派发过、或注册中心读不到）→ 0** —— 调用方要自己决定兜底值
     */
    public int readShardCount(String indexerRole) {
        KV kv = client().getWithStatus(shardsKeyOf(indexerRole));
        if (kv == null || kv.getData() == null) {
            return 0;
        }
        try {
            return Integer.parseInt(new String(kv.getData(), StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException e) {
            log.warn("分片数的值不是数字，当作没有：{}", new String(kv.getData(), StandardCharsets.UTF_8));
            return 0;
        }
    }

    private static String shardsKeyOf(String indexerRole) {
        return SHARDS_ROOT + "/" + indexerRole;
    }

    // ------------------------------------------------------------------

    /**
     * 取与注册中心的长连接。
     *
     * <p><b>每次现取</b>，不缓存 —— 掉线重连时 {@link NodeRegistry#client()} 会换成另一个实例。
     */
    private JRegistryClient client() {
        JRegistryClient client = nodeRegistry.client();
        if (client == null || !client.isConnected()) {
            throw new IllegalStateException("注册中心未连接，读不到也写不了索引轮次");
        }
        return client;
    }

    private static byte[] bytes(long round) {
        return String.valueOf(round).getBytes(StandardCharsets.UTF_8);
    }
}
