package hawk.cloud.indexer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把一个索引目录打成 ZIP，并算它的 SHA-256。
 *
 * <h2>为什么单独一个类</h2>
 * 原来是"推"那条链路上的私有方法。改成拉取模型之后，索引器只需要**把包装好等着被拉走**，
 * 打包这件事与"谁拉、什么时候拉"无关，所以单独抽出来。
 *
 * <p>入口只有一个：{@link IndexDownloadController}。
 *
 * <p>值得一提的是 entry 名必须用**相对路径**：向量索引有每字段的子目录，
 * 用错了打出来的包解压出来是个平铺的垃圾堆，而且要等到召回端加载失败才发现。
 */
public final class IndexPackager {

    private static final Logger log = LoggerFactory.getLogger(IndexPackager.class);

    private IndexPackager() {
    }

    /**
     * 打包。
     *
     * <p><b>用 STORED 不压缩</b> —— 索引文件本身已经是 LZ4 / 量化过的，再压一遍几乎不省，还费 CPU。
     *
     * @param tmpDir 临时文件放哪；空则用 {@code java.io.tmpdir}
     * @return 临时 zip 的路径；**调用方负责删**
     */
    public static Path pack(Path indexDir, String tmpDir) throws IOException {
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

    /** 算文件的 SHA-256，十六进制小写。 */
    public static String sha256Of(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file);
             DigestOutputStream out = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
            in.transferTo(out);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 删临时包，删不掉只记 debug（留给操作系统清临时目录）。 */
    public static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.debug("删临时 zip 失败：{}", p);
        }
    }
}
