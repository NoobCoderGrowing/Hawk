package hawk.cloud.indexer;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import io.github.noobcodergrowing.jregistrycore.KV;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把建好的索引推给本次分片的召回节点（方案 §3、§4.1、§6.2）。
 *
 * <h2>它做四件事</h2>
 * <ol>
 *   <li>查注册中心，列出 {@code cloud/{recallRole}/{shardNum}} 下**在线**的副本</li>
 *   <li>把索引目录**整棵打成 ZIP**（JDK 内建，不加依赖），同时算出 SHA-256</li>
 *   <li>逐个副本推送：先查剩余磁盘，再 {@code POST /index/{text|vector}}，失败重试</li>
 *   <li>返回 {@code pushed} / {@code failed} 两个副本 id 列表</li>
 * </ol>
 *
 * <h2>与 CoordinatorNotifier 的形状像，但不是一类</h2>
 * 两者都用 {@code HttpClient}、都"尽力而为"；但**失败语义不同** ——
 * 通知失败只记日志，而**推送失败要影响"是否通知协调器"**（一个副本都没成功就不通知）。
 * 所以分开两个类，不要合并。
 *
 * <h2>串行推</h2>
 * 副本之间无依赖，串行的日志与失败处理都直观；20 MB 局域网约 1 秒，3 副本 3 秒可接受。
 * 要并行的话把那个 for 换成线程池即可，接口不变。
 */
@Service
public class IndexPusher {

    private static final Logger log = LoggerFactory.getLogger(IndexPusher.class);

    /** 注册中心里的根路径。**不带前导斜杠**（与 NodeRegistry 一致）。 */
    private static final String ROOT = "cloud";

    private final RegistryRegistrar registrar;

    /**
     * 单次推送的超时。默认 **10 分钟** —— 按 200 MB 级分片、慢网/跨机房留的余量
     * （方案 §12.8）。注意它和"协调器 → 索引器 {@code /repush}"那个秒级超时**不是一回事**。
     */
    @Value("${hawk.cloud.indexer.push.timeout-ms:600000}")
    private long pushTimeoutMs;

    /** 每个副本的推送重试次数。网络抖动很常见，一次不成就放弃太脆（§6.1）。 */
    @Value("${hawk.cloud.indexer.push.retry:3}")
    private int retryTimes;

    /**
     * 磁盘阈值倍数：要求 {@code freeBytes ≥ 包大小 × 它}。
     *
     * <p>为什么是 2：召回端要**先落一份 zip、再解压出一份目录**，峰值是两份（§12.9a）。
     */
    @Value("${hawk.cloud.indexer.push.disk-factor:2}")
    private int diskFactor;

    /** 推送用的临时 zip 放这里，推完就删（§14.4）。 */
    @Value("${hawk.cloud.indexer.push.tmp-dir:}")
    private String tmpDir;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public IndexPusher(RegistryRegistrar registrar) {
        this.registrar = registrar;
    }

    /** 补推的结果：三种状态各自对应一个 HTTP 码（方案 §12.5⑤）。 */
    public record RepushOutcome(Status status, PushResult result) {
        public enum Status {
            /** 已受理（**推的活还在后台跑** —— 结果走 /indexComplete 回调，不从这里回）。 */
            OK,
            /** 抢不到 Lock2：本节点正在推送/清理 —— 协调器下一轮再来。 */
            BUSY,
            /** 本地已经没有这个 stamp 了（被保留策略清掉）—— 协调器删记录 + 告警。 */
            GONE
        }

        public static RepushOutcome busy() {
            return new RepushOutcome(Status.BUSY, null);
        }

        public static RepushOutcome gone() {
            return new RepushOutcome(Status.GONE, null);
        }

        /** 已受理；**推的活还没干完** —— 结果不从这里回，走 {@code /indexComplete} 回调。 */
        public static RepushOutcome ok() {
            return new RepushOutcome(Status.OK, null);
        }
    }

    /** 一次推送的结果。两个列表都是**副本 id**（如 {@code id-0}），不是地址 —— 地址会变，id 不会。 */
    public record PushResult(List<String> pushed, List<String> failed) {
        /** 一个副本都没成功。协调器据此决定不通知（§6.2）。 */
        public boolean allFailed() {
            return pushed.isEmpty();
        }
    }

    /**
     * 把 {@code indexDir} 推给本分片的召回节点。
     *
     * @param recallRole 目标角色：{@code text-recall} 或 {@code vector-recall}
     * @param shardNum   本节点的分片名（<b>与目标一致</b> —— 两侧分片规则相同）
     * @param stamp      索引的版本，同时是召回端的目录名
     * @param kind       {@code text} / {@code vector}，决定打哪个端点
     * @param indexDir   索引目录（会被整棵打包）
     * @param only       只推这些副本 id；**null = 推给所有在线副本**（补推时传失败的那几个）
     */
    public PushResult push(String recallRole, String shardNum, String stamp, String kind,
                           Path indexDir, List<String> only) {
        List<KV> kvs = registrar.listNodes(ROOT + "/" + recallRole + "/" + shardNum);
        List<Target> targets = new ArrayList<>();
        for (KV kv : kvs) {
            // 上下线由注册中心说了算 —— 推到已下线的副本上只会白等超时（§2.2）
            if (kv.isOffline()) {
                continue;
            }
            String id = replicaIdOf(kv.getKey());
            if (id == null) {
                continue;
            }
            if (only != null && !only.contains(id)) {
                continue;
            }
            targets.add(new Target(id, new String(kv.getData(), StandardCharsets.UTF_8)));
        }
        if (targets.isEmpty()) {
            // "没有在线副本 = 不需要推送"（§12.5 ①）—— 不是失败、不记录、不重试
            log.info("{} {} 下没有需要推送的在线副本（only={}）", recallRole, shardNum, only);
            return new PushResult(List.of(), List.of());
        }

        Path zip;
        String sha256;
        long zipBytes;
        try {
            zip = packZip(indexDir);
            zipBytes = Files.size(zip);
            sha256 = sha256Of(zip);
        } catch (Exception e) {
            // 打包失败 = 这一轮整体失败，所有目标都记 failed
            log.error("打包索引失败：{}", indexDir, e);
            List<String> ids = targets.stream().map(Target::id).toList();
            return new PushResult(List.of(), ids);
        }

        List<String> pushed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        try {
            for (Target t : targets) {
                if (pushOne(t, recallRole, shardNum, stamp, kind, zip, zipBytes, sha256)) {
                    pushed.add(t.id());
                } else {
                    failed.add(t.id());
                }
            }
        } finally {
            deleteQuietly(zip);     // 临时 zip 不留在磁盘上（§14.4）
        }
        log.info("{} {} stamp={} 推送完成：成功 {}，失败 {}", recallRole, shardNum, stamp, pushed, failed);
        return new PushResult(pushed, failed);
    }

    // ------------------------------------------------------------------ 单个副本

    private boolean pushOne(Target target, String recallRole, String shardNum, String stamp,
                            String kind, Path zip, long zipBytes, String sha256) {
        // 磁盘不够就**一个字节都不传**，直接记失败 —— 不白传一遍（§12.9a）。
        // 注意：freeBytes 是监控信号不是门禁，查不到要照推（见 checkDisk 的注释）
        if (!hasRoomFor(target, zipBytes)) {
            log.warn("{} 剩余空间不足（要 {}, 需 {}），跳过推送", target.id(), zipBytes, zipBytes * diskFactor);
            return false;
        }

        String url = "http://" + target.address() + "/index/" + kind
                + "?role=" + recallRole + "&shard=" + shardNum + "&stamp=" + stamp;
        for (int attempt = 1; attempt <= Math.max(1, retryTimes); attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMillis(pushTimeoutMs))
                        .header("Content-Type", "application/zip")
                        .header("X-Content-SHA256", sha256)
                        .POST(HttpRequest.BodyPublishers.ofFile(zip))
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return true;
                }
                // 409 = 对方正在收另一份（锁被占）；422 = checksum 或内容不对；5xx = 对方出错。
                // 都靠重试兜 —— 但 422 重传同一份通常还是错，只是不值得为它单独写分支
                log.warn("推 {} 返回 {}（第 {}/{} 次）：{}",
                        target.id(), response.statusCode(), attempt, retryTimes, response.body());
            } catch (Exception e) {
                log.warn("推 {} 失败（第 {}/{} 次）：{}", target.id(), attempt, retryTimes, e.toString());
            }
            if (attempt < retryTimes) {
                sleep(1000L << (attempt - 1));      // 1s / 2s / 4s
            }
        }
        return false;
    }

    /**
     * 查对方的剩余磁盘够不够。
     *
     * <p><b>查不到就返回 true（照推）</b> —— 这条反直觉，但它是刻意的：
     * {@code freeBytes} 是个**监控信号，不是门禁**。{@code /health} 挂了不等于磁盘满，
     * 而数据通路不该被一个监控端点的可用性挡住。反过来（查不到就不推）会让
     * {@code /health} 的单点故障扩散成**整条推送链路的故障**。
     */
    private boolean hasRoomFor(Target target, long zipBytes) {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://" + target.address() + "/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return true;
            }
            JSONObject body = JSON.parseObject(response.body());
            Long free = body.getLong("freeBytes");
            if (free == null) {
                return true;                    // 老版本没有这个字段 —— 照推
            }
            return free >= zipBytes * diskFactor;
        } catch (Exception e) {
            log.debug("查 {} 的剩余空间失败，照推：{}", target.id(), e.toString());
            return true;
        }
    }

    // ------------------------------------------------------------------ 打包

    /** 把索引目录整棵打成 ZIP。用 STORED 不压缩 —— 索引文件本身已经是 LZ4/量化过的。 */
    private Path packZip(Path indexDir) throws IOException {
        Path dir = (tmpDir == null || tmpDir.isBlank())
                ? Path.of(System.getProperty("java.io.tmpdir")) : Path.of(tmpDir);
        Files.createDirectories(dir);
        Path zip = Files.createTempFile(dir, "hawk-index-", ".zip");
        try (OutputStream fileOut = Files.newOutputStream(zip);
             ZipOutputStream zipOut = new ZipOutputStream(new BufferedOutputStream(fileOut));
             Stream<Path> walk = Files.walk(indexDir)) {

            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                // entry 名用**相对路径** —— vector 索引有每字段的子目录，必须保留
                String entry = indexDir.relativize(p).toString().replace('\\', '/');
                zipOut.putNextEntry(new ZipEntry(entry));
                Files.copy(p, zipOut);
                zipOut.closeEntry();
            }
        }
        return zip;
    }

    private static String sha256Of(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file);
             DigestOutputStream out = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
            in.transferTo(out);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    // ------------------------------------------------------------------ 小工具

    /** {@code cloud/text-recall/shard-0/id-0} → {@code id-0}；形状不对返回 null。 */
    private static String replicaIdOf(String key) {
        String[] parts = key.split("/");
        return parts.length >= 4 ? parts[parts.length - 1] : null;
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.debug("删临时 zip 失败：{}", p);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 一个推送目标：副本 id + {@code ip:port}。 */
    private record Target(String id, String address) {
    }
}
