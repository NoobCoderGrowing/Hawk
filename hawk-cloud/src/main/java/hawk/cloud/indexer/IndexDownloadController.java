package hawk.cloud.indexer;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 让召回端把索引拉走：{@code GET /index/download}。方案见 {@code DESIGN.md} §4.2。
 *
 * <pre>
 * GET /index/download?shard=shard-0&amp;round=11
 *     -> 200  Content-Type: application/zip
 *             X-Content-SHA256: &lt;64 位十六进制&gt;
 *             &lt;zip 字节&gt;
 *     -> 404  本地没有这一轮（被保留策略清了、或从没建过）
 *     -> 400  shard 与本节点不符
 * </pre>
 *
 * <h2>为什么要有缓存</h2>
 * "推"的模型里索引器打包**一次**、发给 N 个召回副本；"拉"的模型里 N 个副本**各自来拉** ——
 * 不缓存就是打包 N 次。索引本身已经是 LZ4 / 量化过的，STORED 打包很便宜，但 200 MB 级的目录
 * 树每次都 walk 一遍仍然不值得。所以这里按 round 缓存一份打好的包，round 变了就重打并删旧的。
 *
 * <h2>校验 shard，不校验 round</h2>
 * 落地路径的两段（{@code index-dir}/{role}/{shard}）全部来自本节点配置，只有 round 来自请求。
 * 不校验 shard 的话，一个配成 {@code shard-0} 的节点会把 {@code shard-1} 的索引**照发**出去 ——
 * 而且路径是它自己拼的、看起来完全正常。round 只用来找目录，找不到就是 404，没有越界风险
 * （它进不了路径拼接，只参与比较）。
 */
@RestController
public class IndexDownloadController {

    private static final Logger log = LoggerFactory.getLogger(IndexDownloadController.class);

    /** 校验和的响应头名。**必须与召回端读的名字一致**（那边是 IndexFetchClient）。 */
    public static final String SHA256_HEADER = "X-Content-SHA256";

    private final IndexReadyRegistry readyRegistry;
    private final IndexService textService;
    private final VectorIndexService vectorService;

    @Value("${hawk.cloud.indexer.role}")
    private String role;

    @Value("${hawk.cloud.indexer.shard-num}")
    private String shardNum;

    /** 打包用的临时目录；留空则用 {@code java.io.tmpdir}。 */
    @Value("${hawk.cloud.indexer.pack.tmp-dir:}")
    private String tmpDir;

    /** 打好的包 + 它是哪一轮的 + 它的摘要。**不可变**，换轮次时整体替换。 */
    private record Packed(long round, Path zip, String sha256) {
    }

    /** 当前缓存。**只在 {@link #packFor} 的同步块里读写。** */
    private Packed cached;

    public IndexDownloadController(IndexReadyRegistry readyRegistry,
                                   IndexService textService, VectorIndexService vectorService) {
        this.readyRegistry = readyRegistry;
        this.textService = textService;
        this.vectorService = vectorService;
    }

    @GetMapping("/index/download")
    public ResponseEntity<?> download(@RequestParam(name = "shard") String shard,
                                      @RequestParam(name = "round") long round) {
        if (!this.shardNum.equals(shard)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "shard 不匹配：本节点是 " + this.shardNum + "，请求是 " + shard));
        }

        Path indexDir = findIndexDirOfRound(round);
        if (indexDir == null) {
            log.info("被请求第 {} 轮，但本地没有（已被保留策略清掉或从没建过）", round);
            return ResponseEntity.status(404).body(Map.of(
                    "message", "本地没有第 " + round + " 轮的索引"));
        }

        try {
            Packed packed = packFor(round, indexDir);
            return ResponseEntity.ok()
                    .header(SHA256_HEADER, packed.sha256())
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(Files.size(packed.zip())))
                    .body(new org.springframework.core.io.FileSystemResource(packed.zip()));
        } catch (Exception e) {
            log.error("打包第 {} 轮失败（{}）", round, indexDir, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    /**
     * 取（或打）第 {@code round} 轮的包。
     *
     * <p>{@code synchronized}：多个召回副本会同时来拉，而打包要写临时文件。加锁的代价可以忽略 ——
     * 同一轮只会真打一次，之后都是命中缓存直接返回。
     */
    private synchronized Packed packFor(long round, Path indexDir) throws Exception {
        if (cached != null && cached.round() == round && Files.isRegularFile(cached.zip())) {
            return cached;
        }
        Path zip = IndexPackager.pack(indexDir, tmpDir);
        String sha = IndexPackager.sha256Of(zip);
        Packed previous = cached;
        cached = new Packed(round, zip, sha);
        IndexPackager.deleteQuietly(previous == null ? null : previous.zip());   // 换轮次了，旧的没用
        log.info("第 {} 轮打包完成：{}（{} 字节）", round, zip, Files.size(zip));
        return cached;
    }

    /**
     * 本节点「第 {@code round} 轮」的索引目录。
     *
     * <p>借的是两个 service 各自的 {@code findStampDirOfRound}（那里还管着"取最新那份"的口径）。
     * 它们都是私有的，所以这里按 role 转发一下 —— 不重复实现一份扫描逻辑。
     */
    private Path findIndexDirOfRound(long round) {
        return switch (role) {
            case "text-indexer" -> textService.indexDirOfRound(round);
            case "vector-indexer" -> vectorService.indexDirOfRound(round);
            default -> {
                log.warn("本节点 role={} 不是索引器，不该收到下载请求", role);
                yield null;
            }
        };
    }

    @PreDestroy
    public void cleanup() {
        synchronized (this) {
            IndexPackager.deleteQuietly(cached == null ? null : cached.zip());
            cached = null;
        }
    }
}
