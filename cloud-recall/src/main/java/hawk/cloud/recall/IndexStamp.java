package hawk.cloud.recall;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * 索引目录的时间戳 —— 召回器与索引器共用的**跨模块契约**。
 *
 * <h2>格式</h2>
 * {@code yyyy-MM-dd-HH-mm-ss}，固定宽度、零填充，所以**字典序 == 时间序**，
 * 排序即最新（召回端靠它认当前版本）。
 *
 * <h2>⚠️ 这个常量有两份，必须保持一致</h2>
 * 不进 {@code core} —— 那是基础索引 SDK（索引文件格式、文档模型），而"索引目录怎么按时间
 * 分版本"是 **cloud 调度层的约定**，单机的 {@code BuildIndex} 根本不需要它。
 *
 * <p>代价是**同一个格式有两份定义、会漂移**，而且这是**改错不报错**的那一类：
 * 索引器写 {@code 2026-10-14-10-52-49/}、召回端按别的格式理解的话，路径就对不上。
 *
 * <p><b>改这里必须同改 {@code hawk.cloud.indexer.IndexStamp}</b>，反之亦然。
 * 两侧的 {@code isValid} 要用同一份用例（含路径穿越的恶意输入）。
 */
public final class IndexStamp {

    public static final String PATTERN = "yyyy-MM-dd-HH-mm-ss";

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern(PATTERN);

    /** 形状：6 段、每段定长数字。**先拦这个，再 parse**。 */
    private static final Pattern SHAPE =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}");

    private IndexStamp() {
    }

    /** 建索引时**生成一次**，之后（含推送重试、重推）都用它 —— 见方案 §5.4。 */
    public static String now() {
        return of(LocalDateTime.now());
    }

    public static String of(LocalDateTime time) {
        return FORMAT.format(time);
    }

    /**
     * 校验一个来自请求的 stamp。
     *
     * <p><b>这是安全边界，不是格式偏好。</b> stamp 会被**当成目录名**拼进路径：
     * <pre>
     *   Path dir = Path.of(indexDirRoot, role, shardNum, stamp);
     * </pre>
     * 不校验的话 {@code "../../etc/foo"} 会在索引根之外建目录（召回端）、或在索引根之外
     * 找目录（索引器的 {@code /repush}）。
     *
     * <p>两道：**正则**排除掉 {@code /} 和 {@code .}（路径穿越需要的那两样），
     * 再 **parse 一次**确认是真实日期 —— 只匹配正则的话 {@code 9999-99-99} 也会过。
     */
    public static boolean isValid(String stamp) {
        if (stamp == null || !SHAPE.matcher(stamp).matches()) {
            return false;
        }
        try {
            LocalDateTime.parse(stamp, FORMAT);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
