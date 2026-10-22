package hawk.cloud.indexer;

import com.alibaba.fastjson.JSONObject;

import io.github.noobcodergrowing.jregistryclient.JRegistryClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * 把「我这儿有第 R 轮的索引」登记到注册中心。方案见 {@code INDEX_PULL_MODEL_DESIGN.md} §3.1。
 *
 * <pre>
 *   cloud/index-ready/{indexerRole}/{shardNum}/{shardId}  ->  {"round":11,"version":"v0000000011-2026-10-15-01-01-23"}
 * </pre>
 *
 * <h2>这是"拉"模型的核心</h2>
 * 索引器**不再需要知道有哪些召回节点**：它只登记一句话，谁拉、拉几次、什么时候拉都与它无关。
 * 加一个召回副本对索引器**零影响**，而且新副本下一个扫描周期就能自己把索引拉回来 ——
 * 这是"推"模型做不到的（推的模型里新副本要等下一次重建）。
 *
 * <h2>值里的两个字段</h2>
 * <ul>
 *   <li>{@code round} —— 召回端拿它跟自己现在的轮次比，决定要不要拉；协调器的 round tracker 也读它。</li>
 *   <li>{@code version} —— **版本目录名**（{@code v{零填充轮次}-{stamp}}），召回端装的时候用它当目录名。
 *       {@code IndexStore} 的"当前版本 = 目录名最大"依赖它。</li>
 * </ul>
 * <b>⚠️ {@code version} 是从注册中心读来、又被当成目录名拼进路径的</b> ——
 * 召回端**必须**用 {@code IndexStamp.isValidVersion} 校验之后再落地（那边有说明）。
 *
 * <h2>⚠️ 一个必须守的不变量</h2>
 * <b>登记的那个 round 必须真的在盘上。</b>索引器会 {@code pruneOldStamps}（保留最新 2 份）——
 * 如果它把第 R 轮的目录删了而登记里还写着 R，召回端就会**每次扫描都拉一次、每次都 404**，
 * 而且日志每 {@code pull-interval-ms} 刷一条。所以 {@link #announceCurrent} 会在 prune 之后的下一个心跳里重新登记一遍。
 * 这不是优化，是不变量。
 */
@Component
public class IndexReadyRegistry {

    private static final Logger log = LoggerFactory.getLogger(IndexReadyRegistry.class);

    /** 登记路径的根。**兄弟路径**，不要挂在节点登记那棵树下面（那个的值是裸的 ip:port，会被解析坏）。 */
    private static final String ROOT = "cloud/index-ready";

    private static final String VALUE_TYPE = "string";

    private final RegistryRegistrar registrar;

    @Value("${hawk.cloud.indexer.index-dir:cloud/index}")
    private String indexDirRoot;

    @Value("${hawk.cloud.indexer.role}")
    private String role;

    @Value("${hawk.cloud.indexer.shard-num}")
    private String shardNum;

    /** 上一次登记的内容，只为少刷日志。 */
    private volatile String lastAdvertised;

    public IndexReadyRegistry(RegistryRegistrar registrar) {
        this.registrar = registrar;
    }

    /**
     * 心跳式重登记「盘上最新那一份是哪一轮」。
     *
     * <h2>为什么必须有这个心跳</h2>
     * 注册中心的 {@code offline} 标记是跟着**客户端会话**走的：索引器一重启，旧会话的 key 立刻变
     * offline，而**只有构建才会写这条键** —— 于是重启之后它一直离线，直到下一次构建。
     * 后果是：**重启后这个索引器就拉不到了**，新上线的召回副本也拿不到它已有的索引
     * （而"新副本自己拉回来"正是这个方案最想要的性质）。
     *
     * <p>所以这里定时把盘上最新那一份重登记一次。它同时解决两件事：
     * ① 重启后立刻恢复可达；② 万一登记被谁删了，一个心跳周期内自动补上。
     *
     * <p>重登记的依据是**磁盘**（扫本分片目录、取目录名最大且有 {@code round} 文件的那份），
     * 不是内存 —— 内存里那份重启就没了，而磁盘上那份才是召回端真能拉到的东西。
     * 这也顺带保证了 §5.2 那个不变量：**登记的那一轮一定在盘上**。
     */
    @Scheduled(fixedDelayString = "${hawk.cloud.indexer.register-interval-ms:60000}",
               initialDelayString = "${hawk.cloud.indexer.register-interval-ms:60000}")
    public void announceCurrent() {
        Path shardDir = Path.of(indexDirRoot, role, shardNum);
        if (!Files.isDirectory(shardDir)) {
            return;                                     // 还没建过任何一份，没什么可登记的
        }
        Path newest;
        try (var dirs = Files.list(shardDir)) {
            newest = dirs.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> IndexRound.read(p) != null)     // 没有 round 文件的（旁路建的 / 半成品）不算
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        } catch (IOException e) {
            log.warn("扫 {} 找最新一轮失败：{}", shardDir, e.toString());
            return;
        }
        if (newest == null) {
            return;
        }
        Long round = IndexRound.read(newest);
        String version = newest.getFileName().toString();     // v{轮次}-{stamp}，就是召回端要用的目录名
        String value = round + "|" + version;
        if (value.equals(lastAdvertised)) {
            log.debug("索引就绪登记未变化，仍然重发一次：{}", value);
        } else {
            log.info("索引就绪登记（最新一份）：round={} version={}", round, version);
        }
        lastAdvertised = value;
        advertise(round, version);
    }

    /** {@code cloud/index-ready/{indexerRole}/{shardNum}/{shardId}}。静态以便单测。 */
    static String keyOf(String indexerRole, String shardNum, String shardId) {
        return ROOT + "/" + indexerRole + "/" + shardNum + "/" + shardId;
    }

    /**
     * 登记「本节点有第 {@code round} 轮，目录名是 {@code version}」。
     *
     * <p><b>时机</b>：{@code commit()} 之后、写 {@code round} 文件之后。顺序不能反 ——
     * 登记的那一刻起召回端就可能来拉，而拉的是磁盘上的东西，必须已经完整。
     *
     * <p>失败只记日志：注册中心不通不该让一次成功的构建变成失败。代价是这个节点暂时不会被拉到，
     * 下次构建会再登记一次。
     */
    public void advertise(long round, String version) {
        JRegistryClient client = registrar.clientOrNull();
        if (client == null || !client.isConnected()) {
            log.warn("注册中心未连接，跳过索引就绪登记（round={}，version={}）", round, version);
            return;
        }

        JSONObject value = new JSONObject();
        value.put("round", round);
        value.put("version", version);

        String key = keyOf(registrar.role(), registrar.shardNum(), registrar.shardId());
        client.set(key, value.toJSONString().getBytes(StandardCharsets.UTF_8), VALUE_TYPE);
        log.info("已登记索引就绪：{} -> {}", key, value.toJSONString());
    }
}
