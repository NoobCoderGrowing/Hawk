package hawk.cloud.recall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 读索引目录里的 {@code round} 文件 —— 这一份索引是**第几轮**构建的。
 * 方案见 {@code INDEX_BUILD_DISPATCH_DESIGN.md} §7.3。
 *
 * <h2>这个文件从哪来</h2>
 * 不是本模块写的，是**索引器**写的：它在 commit 之后把轮次写进索引目录，
 * 然后被整目录打成 zip，由本模块的 {@code IndexPullJob} 拉下来，{@code IndexStore.install} 整目录原子切换 ——
 * 所以这个文件跟着索引一路到了这里，本模块**不需要任何额外代码**去接收它。
 *
 * <h2>本模块只读不写</h2>
 * 协调器的 round tracker 判"这一轮到底有没有副本建成"，读的就是
 * {@code /health} 里由本类解析出来的那个数。**本模块对轮次没有任何话语权**，
 * 它只是一个忠实的搬运工。
 *
 * <p><b>这个类是 {@code hawk.cloud.indexer.IndexRound} 的副本</b>（和 {@link IndexStamp} 一样，
 * 两个服务是两个独立的 Spring Boot 应用，没有共享模块）。<b>{@link #FILE_NAME} 必须与那边一致</b> ——
 * 不一致的话 tracker 会永远判"未收敛"，而且除了"一直重建"之外不会有任何报错。
 */
public final class IndexRound {

    private static final Logger log = LoggerFactory.getLogger(IndexRound.class);

    /** 文件名。**必须与 {@code hawk.cloud.indexer.IndexRound.FILE_NAME} 一致。** */
    public static final String FILE_NAME = "round";

    private IndexRound() {
    }

    /**
     * 读某个索引目录的轮次。
     *
     * @return 轮次；文件不存在（旁路调用建的索引、或从没经协调器建过的老索引）或内容不是数字 → {@code null}
     */
    public static Long read(Path indexDir) {
        Path file = indexDir.resolve(FILE_NAME);
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return Long.parseLong(Files.readString(file, StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException e) {
            log.warn("读 {} 的轮次失败：{}", file, e.toString());
            return null;
        }
    }
}
