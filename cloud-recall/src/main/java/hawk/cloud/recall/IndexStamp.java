package hawk.cloud.recall;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * 索引目录的版本命名 —— 召回器与索引器共用的**跨模块契约**。
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

    /**
     * 版本目录名的前缀。
     *
     * <h2>为什么目录名前缀是 {@code v} 而不是直接写轮次</h2>
     * 版本目录名 = {@code v{零填充轮次}-{stamp}}，排序由**轮次**决定，不再由各台机器的本地时钟决定
     * （召回端"当前版本 = 目录名字典序最大"，而拉取模型下一个分片的索引可能来自不同的索引器副本，
     * 时钟偏一点就会让新一轮的名字排到旧一轮后面 —— 读端取旧索引，而每个节点各自看起来都正常）。
     *
     * <p>前缀用字母而不是数字，是为了**升级迁移**：旧的目录名是纯 {@code {stamp}}（以 '2' 开头），
     * 而所有新名字以 'v' 开头 —— 字典序上 {@code 'v'(118) > '2'(50)}，新版本一律排在旧版本之后。
     * 若不用前缀（{@code 0000000021-...} 以 '0' 开头），旧目录会**永远压在**新目录上面，
     * {@code newest()} 会一直返回那份旧的，而且不报任何错。
     */
    public static final String VERSION_PREFIX = "v";

    /** 轮次在目录名里的固定宽度。零填充，所以字典序 == 数值序。10 位够用很久。 */
    public static final int ROUND_WIDTH = 10;

    /** 形状：{@code v} + 定长轮次 + {@code -} + stamp。 */
    private static final Pattern VERSION_SHAPE =
            Pattern.compile("v\\d{" + ROUND_WIDTH + "}-\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}");

    private IndexStamp() {
    }

    /**
     * 由（轮次, 时间戳）算出**版本目录名**：{@code v0000000021-2026-10-15-01-43-01}。
     *
     * <p>索引器和召回端各自算各自的 —— 两侧**不需要算出一样的东西**（那是本地目录名），
     * 只要都按"轮次优先"排就行。这一点很重要：就算哪天两侧的规则漂了，也只是目录名不同，
     * 不会出现"版本对不上"那种硬错误。
     *
     * @param round 轮次；旁路调用（没有轮次）传 0 —— 它会排在所有真实轮次之前
     */
    public static String versionName(long round, String stamp) {
        return VERSION_PREFIX + String.format("%0" + ROUND_WIDTH + "d", round) + "-" + stamp;
    }

    /**
     * 校验一个**版本目录名**。
     *
     * <p><b>这是安全边界，不是格式偏好。</b> 在拉取模型里这个名字是从**注册中心**读来的
     * （索引器登记的内容），然后被当成目录名拼进路径：
     * <pre>
     *   Path dir = shardDir.resolve(version);
     * </pre>
     * 不校验的话，往注册中心里塞一个 {@code "../../../tmp/evil"} 就能让召回端把索引装到分片目录之外。
     *
     * <p>两道：**正则**排除掉 {@code /} 和 {@code .}（路径穿越需要的那两样），
     * 再 **parse 一次**里面的 stamp 确认是真实日期 —— 只匹配正则的话 {@code 9999-99-99} 也会过。
     */
    public static boolean isValidVersion(String version) {
        if (version == null || !VERSION_SHAPE.matcher(version).matches()) {
            return false;
        }
        return isValid(version.substring(VERSION_PREFIX.length() + ROUND_WIDTH + 1));
    }

    /** 建索引时**生成一次**，之后（含推送重试、重推）都用它 —— 见方案 §5.4。 */
    public static String now() {
        return of(LocalDateTime.now());
    }

    public static String of(LocalDateTime time) {
        return FORMAT.format(time);
    }

    /**
     * 校验一个纯 stamp（{@code yyyy-MM-dd-HH-mm-ss}）。
     *
     * <p>{@link #isValidVersion} 用它校验版本名里的那一段。**光有它已经不够了** ——
     * 目录名现在是 {@code v{轮次}-{stamp}}，整名要靠 {@link #isValidVersion}。
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
