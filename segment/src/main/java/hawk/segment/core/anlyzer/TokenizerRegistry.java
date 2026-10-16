package hawk.segment.core.anlyzer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * tokenizer 名字 → 实例的**唯一登记处**。索引与倒排两侧都从这里取实例。
 *
 * <h2>名字</h2>
 * 格式是 {@code <实现名>-<参数>}，今天是 {@code nshortest-<正整数>}（N-最短路径，n 是窗口大小），
 * 如 {@code nshortest-1}、{@code nshortest-3}。名字会**逐字写进索引的 fdm**（每个字段记一份），
 * 召回端读出来再送回这里解析 —— 所以名字是跨进程、跨版本的落盘契约，**不许改已有名字的含义**。
 *
 * <p>为什么是"名字"而不是位掩码/枚举序号：字段数是个位数，字节成本可忽略，而名字是自描述的 ——
 * 两年后读一个旧索引仍能看出当初用了什么，即使注册表后来新增了实现。
 *
 * <h2>实例是缓存的，必须缓存</h2>
 * {@link NShortestPathTokenizer} 的词典 {@code NPathSet} 是**实例字段**（容量写死 ~622k），
 * 每次构造都重新从 classpath 读一遍 {@code data/npathword.data}（约 5.87 MB）。
 * 一个字段配两个 tokenizer、索引与召回各一份、召回还可能同时持有新旧两个版本的快照 ——
 * 不缓存的话同一份词典会在堆里放好几份。缓存后**同一个名字全程共享同一个实例**。
 *
 * <p>共享的前提是实例**不可变**。所以 {@link NShortestPathTokenizer} 里那个会写
 * {@code this.n} 的 3 参数 {@code tokenize} 重载已经删掉了 —— 它今天零调用方，
 * 留着一旦被调用就会污染所有共享方（表现为"分词结果莫名变了"，不报错）。
 */
public final class TokenizerRegistry {

    /** 唯一实现的名称前缀。加新实现时在这里加分支，并把名字写进本类的 javadoc。 */
    private static final String NSHORTEST_PREFIX = "nshortest-";

    /**
     * 没显式配 tokenizer 的字段默认用的名字。
     *
     * <p>与加"按字段配 tokenizer"这个能力**之前**的行为一致（那时全仓硬编码 {@code new
     * NShortestPathTokenizer(1)}），所以老调用方一行不改也不会变。放这里而不是让各调用方各写一份
     * 字符串，是为了让这个名字空间只有一个主人。
     */
    public static final String DEFAULT = NSHORTEST_PREFIX + "1";

    /** 名字 → 实例。{@link ConcurrentHashMap} 保证并发下只构造一次。 */
    private static final Map<String, Tokenizer> CACHE = new ConcurrentHashMap<>();

    private TokenizerRegistry() {
    }

    /**
     * 这个名字认不认识。
     *
     * <p>只做**语法**判断（前缀 + 正整数），不构造实例 —— 入口校验一个请求体里的名字时不该付出
     * 加载词典的代价。
     */
    public static boolean isKnown(String name) {
        if (name == null || !name.startsWith(NSHORTEST_PREFIX)) {
            return false;
        }
        return parseN(name.substring(NSHORTEST_PREFIX.length())) >= 1;
    }

    /**
     * 取实例（缓存共享）。
     *
     * @throws IllegalArgumentException 名字不认识 —— 消息里带上可接受的形式，可直接回给客户端
     */
    public static Tokenizer get(String name) {
        if (!isKnown(name)) {
            throw new IllegalArgumentException(
                    "认不出的 tokenizer 名字「" + name + "」。可接受的形式：" + acceptedForms());
        }
        // computeIfAbsent 的映射函数在本例里只会对合法名字返回实例（前面已经校验过），
        // 所以这里不会有"构造抛异常把缓存搞脏"的问题。
        return CACHE.computeIfAbsent(name, TokenizerRegistry::create);
    }

    /** 可接受的名字形式，给错误消息复用（别在两处各写一份措辞）。 */
    public static String acceptedForms() {
        return NSHORTEST_PREFIX + "<正整数>（如 " + NSHORTEST_PREFIX + "1、" + NSHORTEST_PREFIX + "3）";
    }

    /** 只有 {@link #get} 调得到，此时名字已校验过。 */
    private static Tokenizer create(String name) {
        return new NShortestPathTokenizer(parseN(name.substring(NSHORTEST_PREFIX.length())));
    }

    /** 解析不出数字时返回 -1（{@link #isKnown} 据此判否）。 */
    private static int parseN(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
