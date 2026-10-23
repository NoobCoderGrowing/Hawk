package hawk.cloud.indexer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 索引轮次**随索引走**：在索引目录根下放一个 {@code round} 小文件，内容是轮次的十进制文本。
 * 方案见 {@code DESIGN.md} §3.7。
 *
 * <h2>为什么要落盘，而不是只记在内存里</h2>
 * 协调器的 round tracker 靠"召回副本报的 round 等不等于当前轮次"判断这一轮有没有人建成。
 * 节点重启后如果 round 丢了，tracker 会判它"没有这一轮" → 触发一次**毫无必要**的全量重建
 * （向量索引 10 万篇 ≈ 16 分钟）。落盘之后，「重启」和「数据真的变了」才区分得开。
 *
 * <h2>为什么要随索引一起打包</h2>
 * {@code IndexPackager.pack} 是 {@code Files.walk(indexDir)} 把所有普通文件打进去，
 * 所以这个文件**不需要任何额外代码**就会跟着 zip 到召回端；召回端的 {@code IndexStore.install}
 * 是整目录 {@code ATOMIC_MOVE}，所以它也**不需要任何额外代码**就跟着过去了。
 *
 * <h2>为什么不塞进 IndexStamp / 索引文件名</h2>
 * 那两个的格式是**目录排序**的依据（召回端"当前版本 = 目录名字典序最大"）。动它会连累
 * {@code prune} 与 {@code newest}，而且 stamp 是秒级精度，两轮会撞同一个目录名。
 */
public final class IndexRound {

    private static final Logger log = LoggerFactory.getLogger(IndexRound.class);

    /**
     * 文件名。
     *
     * <p><b>不要改。</b>召回端有一个同名副本 {@code hawk.cloud.recall.IndexRound}，
     * 按**同一个名字**在索引目录里找它 —— 两边不一致的话 tracker 会永远判"未收敛"，
     * 而且除了"一直重建"之外**不会有任何报错**。
     */
    public static final String FILE_NAME = "round";

    private IndexRound() {
    }

    /** 把轮次写进索引目录。**必须在 commit 之后、推送之前**（推送要打包这个目录）。 */
    public static void write(Path indexDir, long round) throws IOException {
        Files.writeString(indexDir.resolve(FILE_NAME), String.valueOf(round), StandardCharsets.UTF_8);
    }

    /**
     * 读某个索引目录的轮次。
     *
     * @return 轮次；文件不存在（旁路调用建的索引、或老版本的索引）或内容不是数字 → {@code null}
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
