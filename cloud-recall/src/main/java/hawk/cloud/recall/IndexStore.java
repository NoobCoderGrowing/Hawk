package hawk.cloud.recall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 索引在磁盘上的落法：**解压 → 校验 → 原子切换 → 保留最新 N 份**（方案 §5）。
 *
 * <h2>目录</h2>
 * <pre>
 * {index-dir}/{role}/{shard-num}/
 * ├── .staging-&lt;stamp&gt;/     ← 解压中（点开头，不参与"取最新"也不参与清理）
 * ├── 2026-10-14-10-52-49/   ← 当前版本（目录名最大 = 最新）
 * └── 2026-10-14-09-15-03/   ← 上一版（留 2 份）
 * </pre>
 *
 * <h2>为什么能原子</h2>
 * stamp 精确到秒且由索引器只生成一次，所以**目标目录不存在** ——
 * {@code ATOMIC_MOVE} 是一步到位的真原子操作，没有"先挪走旧的再挪入新的"那个空窗。
 *
 * <h2>"当前版本"就是目录名最大的那个</h2>
 * 格式固定宽度、零填充，所以字典序 == 时间序（{@link IndexStamp}）。不需要指针文件。
 */
public class IndexStore {

    private static final Logger log = LoggerFactory.getLogger(IndexStore.class);

    /** 点开头的一律不参与"取最新"和清理 —— {@code .staging-*} 正在写，动它会连根拔起。 */
    private static final String STAGING_PREFIX = ".staging-";

    private final Path shardDir;

    public IndexStore(Path shardDir) {
        this.shardDir = shardDir;
    }

    public Path shardDir() {
        return shardDir;
    }

    /**
     * 装一份索引：解压到 staging → 校验 → 原子切换 → 清理。
     *
     * <p><b>调用方已经校验过 checksum</b>（收完到临时文件、比对摘要，才调这里）——
     * 所以本方法拿到的是一份**传输完整**的包。校验放在 {@code move} 之前，
     * 不完整的索引从来没有成为过正式目录，也就不存在"切换完才发现是坏的、再删掉"
     * 那个危险窗口（读端可能正好在读）。
     *
     * @throws IOException 解压失败或校验不通过 —— 调用方据此返回错误码，staging 已被删掉
     */
    public void install(String stamp, Path zipFile) throws IOException {
        Files.createDirectories(shardDir);
        Path target = shardDir.resolve(stamp);

        // 幂等：重试用的是同一个 stamp，第二次进来目标已存在。
        // **校验它完整就直接算成功**，不要"挪走再挪入" —— 那等于把刚消掉的窗口又请回来。
        if (Files.isDirectory(target)) {
            log.info("{} 已存在（重试或补推），视为已装好", target);
            return;
        }

        Path staging = shardDir.resolve(STAGING_PREFIX + stamp);
        deleteRecursively(staging);                     // 上一次失败留下的残骸
        try {
            unzip(Files.newInputStream(zipFile), staging);
            verify(staging);
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            log.info("索引已切换：{}", target);
        } catch (IOException e) {
            deleteRecursively(staging);                 // 不完整就删掉，别留下来
            throw e;
        }
        prune();
    }

    /** 解压。**每个 entry 自带 CRC，读完会核对** —— 所以"解压不报错"本身就是一层完整性校验。 */
    private static void unzip(InputStream in, Path staging) throws IOException {
        Files.createDirectories(staging);
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                // 防 zip slip：entry 名里的 ../ 会把文件写到 staging 之外
                Path out = staging.resolve(entry.getName()).normalize();
                if (!out.startsWith(staging)) {
                    throw new IOException("压缩包里的路径越界：" + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * 校验解压出来的东西"像个索引"。
     *
     * <p>方案 §4.2 定的是**只做 checksum**（传输完整性），到这里只剩一件事：确认解出来的
     * 不是空目录。再往深（"打不开"）就要加载索引，代价远大于收益。
     */
    private static void verify(Path staging) throws IOException {
        try (Stream<Path> s = Files.walk(staging)) {
            if (s.noneMatch(Files::isRegularFile)) {
                throw new IOException("压缩包里一个文件都没有");
            }
        }
    }

    /** 保留最新 N 份（默认 2）。 */
    public void prune() {
        prune(2);
    }

    /**
     * 保留最新 {@code keep} 份，其余删掉。
     *
     * <p>三条纪律，每条对着一个坑：
     * <ul>
     *   <li><b>按名字降序、跳过前 keep 个</b> —— 唯一判据就是名字（字典序 == 时间序）</li>
     *   <li><b>点开头的不参与</b> —— 否则 {@code .staging-*} 会因为"最旧"而被优先删掉，
     *       而正在解压的正是它</li>
     *   <li><b>读端取"最新"、清理留"最新 2"</b> —— 两条规则错开一格，
     *       读端刚挑中的那个就不可能被删</li>
     * </ul>
     */
    public void prune(int keep) {
        List<Path> dirs = listVersions();
        for (Path p : dirs.subList(Math.min(keep, dirs.size()), dirs.size())) {
            log.info("保留最新 {} 份，删除旧版本 {}", keep, p.getFileName());
            deleteRecursively(p);
        }
    }

    /** 当前版本 = 目录名最大的那个；没有就返回 null。 */
    public Path newest() {
        List<Path> dirs = listVersions();
        return dirs.isEmpty() ? null : dirs.get(0);
    }

    /** 按名字**降序**的版本目录（不含点开头的）。 */
    public List<Path> listVersions() {
        if (!Files.isDirectory(shardDir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(shardDir)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException e) {
            log.warn("列出 {} 失败：{}", shardDir, e.toString());
            return List.of();
        }
    }

    /** 清掉超过 {@code ageMs} 的 {@code .staging-*} —— 解压失败的残骸靠它兜底。 */
    public void cleanStaleStaging(long ageMs) {
        if (!Files.isDirectory(shardDir)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - ageMs;
        try (Stream<Path> s = Files.list(shardDir)) {
            for (Path p : s.filter(p -> p.getFileName().toString().startsWith(STAGING_PREFIX)).toList()) {
                if (lastModified(p) < cutoff) {
                    log.warn("清理过期的 staging：{}", p.getFileName());
                    deleteRecursively(p);
                }
            }
        } catch (IOException e) {
            log.warn("清理 {} 的 staging 失败：{}", shardDir, e.toString());
        }
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return Long.MAX_VALUE;      // 读不到就当它是新的，别误删
        }
    }

    private static void deleteRecursively(Path p) {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(x -> {
                try {
                    Files.deleteIfExists(x);
                } catch (IOException ignored) {
                    // 删不掉就留着，下次再来
                }
            });
        } catch (IOException e) {
            log.warn("删除 {} 失败：{}", p, e.toString());
        }
    }
}
