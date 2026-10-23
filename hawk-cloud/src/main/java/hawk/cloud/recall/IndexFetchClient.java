package hawk.cloud.recall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 从索引器把一个索引包拉下来，并在**解压之前**验完整性。
 * 方案见 {@code DESIGN.md} §4.3。
 *
 * <h2>与"推"模型里那段接收代码的关系</h2>
 * 逻辑原本在"推"模型里的接收端（收 HTTP 请求体），搬到这边变成"拉"模型的下载端：
 * 流式写临时文件、边写边算 SHA-256、**比对通过才让调用方去解压**。流式是必须的 ——
 * 分片是 200 MB 级的，读进堆里不合适。
 *
 * <h2>校验和写在响应头里</h2>
 * 索引器打包时算好、放在 {@code X-Content-SHA256}（头名两边必须一致）。
 * 它护的是**传输完整性**；解压本身还有一层（ZIP 每个 entry 自带 CRC，读完会核对）。
 * 两条合起来 = 传输完整 + 压缩包完整。
 */
@Component
public class IndexFetchClient {

    private static final Logger log = LoggerFactory.getLogger(IndexFetchClient.class);

    /** 校验和的响应头名。**必须与索引器 IndexDownloadController 发的那个一致。** */
    private static final String SHA256_HEADER = "X-Content-SHA256";

    private static final int CONNECT_TIMEOUT_MS = 2_000;

    /** 一次下载的等待上限。按 200 MB 级、慢网留余量 —— 这是**真的在传数据**，别和"等一个回执"混。 */
    @Value("${hawk.cloud.recall.pull.timeout-ms:600000}")
    private long downloadTimeoutMs;

    /** 下载好的临时包放哪，装完就删。 */
    @Value("${hawk.cloud.recall.pull.tmp-dir:}")
    private String tmpDir;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .build();

    /**
     * 拉下来的包：临时文件 + **版本目录名**（{@code v{轮次}-{stamp}}）。**调用方负责删文件。**
     *
     * <p>目录名是从注册中心传来的，{@link IndexPullJob} 已经用
     * {@code IndexStamp.isValidVersion} 校验过 —— 它会被当成目录名拼进路径，那是安全边界。
     */
    public record Fetched(Path zipFile, String version) {
    }

    /**
     * 拉一轮索引。
     *
     * @param address 索引器的 {@code ip:port}（从注册中心读的）
     * @param shardNum 本节点分片名（索引器会校验，不匹配回 400）
     * @param round   要拉的轮次
     * @param version 版本目录名（**调用方必须已经校验过**）
     * @param stagingDir 临时包放哪（一般就是本分片的目录）
     * @return 拉下来且**校验通过**的包；任何一步失败都返回 {@code null}（调用方记日志、等下一轮）
     */
    public Fetched fetch(String address, String shardNum, long round, String version,
                         Path stagingDir) {
        String url = "http://" + address + "/index/download?shard=" + shardNum + "&round=" + round;
        Path target = null;
        try {
            Files.createDirectories(stagingDir);
            // 名字用 `.staging-` 前缀：拉取中断留下的残骸由 IndexStore.cleanStaleStaging 统一收走
            // （它按这个前缀扫，不区分文件还是目录），不用再写一套清理。
            target = Files.createTempFile(stagingDir, ".staging-pull-" + round + "-", ".zip");

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(downloadTimeoutMs))
                    .GET()
                    .build();

            // 流式写盘 + 同时喂摘要。**不能用 BodyHandlers.ofByteArray()** —— 那会把整包读进堆。
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                log.warn("拉 {} 返回 {}（round={}）", address, response.statusCode(), round);
                closeQuietly(response.body());
                deleteQuietly(target);
                return null;
            }
            String expected = response.headers().firstValue(SHA256_HEADER).orElse(null);
            if (expected == null || expected.isBlank()) {
                log.warn("{} 的响应里没有 {} 头，无法校验，丢弃", address, SHA256_HEADER);
                closeQuietly(response.body());
                deleteQuietly(target);
                return null;
            }

            try (InputStream raw = response.body();
                 DigestInputStream in = new DigestInputStream(raw, digest);
                 OutputStream out = Files.newOutputStream(target,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                in.transferTo(out);
            }

            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(expected)) {
                log.warn("checksum 不符：期望 {} 实得 {}（round={}），丢弃", expected, actual, round);
                deleteQuietly(target);
                return null;
            }
            log.info("已从 {} 拉下第 {} 轮：{}（{} 字节）", address, round, target, Files.size(target));
            return new Fetched(target, version);
        } catch (Exception e) {
            log.warn("从 {} 拉第 {} 轮失败：{}", address, round, e.toString());
            deleteQuietly(target);
            return null;
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (Exception ignored) {
            // 关不掉无所谓，连接迟早被回收
        }
    }

    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignored) {
            // 删不掉就留给 cleanStaleStaging
        }
    }
}
