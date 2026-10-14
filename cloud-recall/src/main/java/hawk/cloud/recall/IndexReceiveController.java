package hawk.cloud.recall;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 接收索引推送。**两个端点**：{@code POST /index/text} 与 {@code POST /index/vector}。
 *
 * <pre>
 * POST /index/vector?role=vector-recall&amp;shard=shard-0&amp;stamp=2026-10-14-10-52-49
 * X-Content-SHA256: 3f2a…（64 位十六进制）
 * Content-Type: application/zip
 * &lt;zip 字节&gt;
 * </pre>
 *
 * <h2>为什么两个端点而不是一个带 type 参数的</h2>
 * 两者的完整性判据不同（方案 §4.2），而且分片目录本来就按 role 分开写。
 * 两个端点各自写死自己的规则更直白，也与索引器侧的形状对称。
 *
 * <h2>锁：覆盖整个请求，一次只允许一个推送</h2>
 * 粒度是**整个请求** —— 接收、摘要、解压、校验、切换、清理全在锁里，不是只锁写文件那一段。
 * 用 {@code tryLock} **不排队**：抢不到立刻回 409，让索引器自己决定怎么重试。
 *
 * <h2>role / shard 是配置，不是请求</h2>
 * 落地路径的前三段（index-dir / role / shard-num）**全部来自本节点配置**，只有 stamp 来自请求。
 * 所以 role 与 shard **必须校验** —— 不校验的话，一个配成 {@code text-recall/shard-0} 的节点
 * 会**照收别处的索引**，把别的分片的数据覆盖进自己这个分片，而且路径是它自己拼的、
 * 看起来完全正常、**没有任何地方会报错**。
 */
@RestController
public class IndexReceiveController {

    private static final Logger log = LoggerFactory.getLogger(IndexReceiveController.class);

    private static final String SHA256_HEADER = "X-Content-SHA256";

    /** 超过这个年龄的 {@code .staging-*} 当成残骸清掉（解压失败 / 传输中断留下的）。 */
    private static final long STALE_STAGING_MS = 60 * 60 * 1000L;

    /**
     * 本节点的索引目录。**与 {@code /health} 读的是同一个实例** —— /health 报的 round
     * 正是从收索引的那个目录里读出来的，两边必须是同一个地方。
     */
    private final LocalIndexStore local;

    /**
     * 每个端点一把锁（与索引器侧对称）。一台 recall 只配一个 role，
     * 所以实际只有一种推送会来、第二把用不上 —— 保持对称是为了将来不用改。
     */
    private final ReentrantLock textLock = new ReentrantLock();
    private final ReentrantLock vectorLock = new ReentrantLock();

    public IndexReceiveController(LocalIndexStore local) {
        this.local = local;
    }

    @PostMapping("/index/text")
    public ResponseEntity<?> receiveText(HttpServletRequest request) {
        return receive(request, "text");
    }

    @PostMapping("/index/vector")
    public ResponseEntity<?> receiveVector(HttpServletRequest request) {
        return receive(request, "vector");
    }

    // ------------------------------------------------------------------

    private ResponseEntity<?> receive(HttpServletRequest request, String kind) {
        String reqRole = request.getParameter("role");
        String reqShard = request.getParameter("shard");
        String stamp = request.getParameter("stamp");
        String expectedSha = request.getHeader(SHA256_HEADER);

        // ---- 三个参数都校验，**都在写任何文件之前**（方案 §5.2）----
        if (!local.role().equals(reqRole)) {
            return badRequest("role 不匹配：本节点是 " + local.role() + "，请求是 " + reqRole);
        }
        if (!local.shardNum().equals(reqShard)) {
            return badRequest("shard 不匹配：本节点是 " + local.shardNum() + "，请求是 " + reqShard);
        }
        // stamp 会被**当成目录名**拼进路径，所以这是安全边界（防路径穿越），不是格式偏好
        if (!IndexStamp.isValid(stamp)) {
            return badRequest("stamp 格式不对：" + stamp);
        }
        if (expectedSha == null || expectedSha.isBlank()) {
            return badRequest("缺少 " + SHA256_HEADER);
        }

        ReentrantLock lock = "text".equals(kind) ? textLock : vectorLock;
        if (!lock.tryLock()) {
            log.info("收到 /index/{} 推送，但本节点正在收索引，回 409", kind);
            return ResponseEntity.status(409).body(Map.of(
                    "status", "receiving",
                    "message", "本节点正在接收索引，稍后再试"));
        }
        try {
            return doReceive(request, kind, stamp, expectedSha);
        } finally {
            lock.unlock();      // 成功失败都要放，否则这个端点再也不会接活
        }
    }

    private ResponseEntity<?> doReceive(HttpServletRequest request, String kind,
                                        String stamp, String expectedSha) {
        IndexStore store = store();
        // 临时 zip 放在分片目录下、用 `.staging-` 前缀 —— 崩了会被 cleanStaleStaging 收走
        Path zip = store.shardDir().resolve(".staging-" + stamp + ".zip");
        try {
            Files.createDirectories(store.shardDir());

            // ---- 1. 边收边写 zip，同时喂摘要。**必须流式**，不能 @RequestBody byte[] ----
            // 那样会把整包读进堆，而分片大小是可配的（§12.8）
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream raw = request.getInputStream();
                 DigestInputStream in = new DigestInputStream(raw, digest);
                 OutputStream out = Files.newOutputStream(zip,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                in.transferTo(out);
            }

            // ---- 2. 摘要比对：**在解压之前** ----
            // 顺带一提，解压本身还有一层：ZIP 每个 entry 自带 CRC，读完会核对。
            // 两条合起来 = 传输完整 + 压缩包完整
            String actualSha = HexFormat.of().formatHex(digest.digest());
            if (!actualSha.equalsIgnoreCase(expectedSha)) {
                log.warn("checksum 不符：期望 {} 实得 {}，丢弃本次推送", expectedSha, actualSha);
                return ResponseEntity.status(422).body(Map.of(
                        "message", "checksum 不符，已丢弃本次推送"));
            }

            // ---- 3. 解压 → 校验 → 原子切换 → 清理（都在 IndexStore 里）----
            store.install(stamp, zip);
            store.cleanStaleStaging(STALE_STAGING_MS);

            log.info("已接收 {} 索引：stamp={} -> {}", kind, stamp, store.newest());
            return ResponseEntity.ok(Map.of("status", "ok", "stamp", stamp));
        } catch (Exception e) {
            log.error("接收索引失败（{}，stamp={}）", kind, stamp, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", String.valueOf(e.getMessage())));
        } finally {
            try {
                Files.deleteIfExists(zip);      // 临时 zip 不留（方案 §14.4）
            } catch (Exception ignored) {
                // 删不掉就留给 cleanStaleStaging
            }
        }
    }

    private IndexStore store() {
        return local.store();
    }

    private static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}
