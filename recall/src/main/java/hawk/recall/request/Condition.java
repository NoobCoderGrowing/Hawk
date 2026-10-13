package hawk.recall.request;

import hawk.recall.query.Occur;

import java.util.List;
import java.util.Objects;

/**
 * 检索请求 —— 用户**原始意图**的表达式，**还没经过分词，因此不可检索**。
 *
 * <h2>为什么它不能直接查索引</h2>
 * {@link Text} 里装的是「老黄冰糖」这么一段**原文**，而索引里存的是「老黄」「冰糖」两个词，
 * 中间差着一次分词。所以必须经 {@link RequestCompiler} 编译成
 * {@link hawk.recall.query.Query 查询 AST} 才能拿去检索。
 *
 * <h2>两层关系</h2>
 * "字段之间是 and 还是 or"只是其中一层。还有一层是**字段内部**词与词之间的关系 ——
 * 两层都有才能完整表达。{@link Text} 同时带这两者：
 *
 * <pre>
 *   new Condition.Text("老黄冰糖",
 *                      List.of("title", "descript"),
 *                      Occur.SHOULD,   // 字段之间：任一字段命中即可
 *                      Occur.MUST)     // 字段内部：词要全中
 * </pre>
 *
 * 编译出来是 {@code SHOULD[ MUST[title:老黄, title:冰糖], MUST[descript:老黄, …] ]}。
 *
 * <h2>字段关系 vs 条件关系</h2>
 * <ul>
 *   <li><b>多个字段</b>之间的关系 → 写在 {@link Text} 的第 3 个参数里</li>
 *   <li><b>多个条件</b>之间的关系 → 用 {@link Group} 包一层</li>
 * </ul>
 * 两者别混：前者是"同一段文本查哪些字段"，后者是"这几个不同条件怎么组合"。
 */
public sealed interface Condition {

    /**
     * 一段文本，在若干字段里检索。
     *
     * @param text          用户输入的原文，**未分词**
     * @param fields        要检索的字段；不能为空
     * @param fieldRelation 字段**之间**的关系（SHOULD = 任一命中；MUST = 每个字段都要命中）
     * @param termRelation  字段**内部**词与词之间的关系（MUST = 词全中；SHOULD = 任一词命中）
     */
    record Text(String text, List<String> fields,
                Occur fieldRelation, Occur termRelation) implements Condition {

        public Text {
            Objects.requireNonNull(text, "text");
            fields = List.copyOf(fields);
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("Text 条件至少要指定一个字段");
            }
            requirePositive(fieldRelation, "fieldRelation");
            requirePositive(termRelation, "termRelation");
        }

        private static void requirePositive(Occur occur, String name) {
            // MUST_NOT 在这里说不通：没有正向子句就谁也匹配不到
            if (occur == Occur.MUST_NOT) {
                throw new IllegalArgumentException(name + " 不能是 MUST_NOT");
            }
        }
    }

    /**
     * 数值区间。
     *
     * <p>原子条件 —— 数值没有"词"的概念，所以没有 termRelation。
     * 它天然是筛选语义（分数恒为 0），编译时会被放进 {@link Occur#FILTER}。
     */
    record Range(String field, double lower, double upper,
                 boolean includeLower, boolean includeUpper) implements Condition {

        public Range {
            Objects.requireNonNull(field, "field");
            if (lower > upper) {
                throw new IllegalArgumentException("下界大于上界：" + lower + " > " + upper);
            }
        }

        /** {@code [lower, upper]} 双闭。 */
        public static Range closed(String field, double lower, double upper) {
            return new Range(field, lower, upper, true, true);
        }

        /** {@code (-∞, upper]} —— 单侧无界。 */
        public static Range atMost(String field, double upper) {
            return new Range(field, Double.NEGATIVE_INFINITY, upper, false, true);
        }

        /** {@code [lower, +∞)} —— 单侧无界。 */
        public static Range atLeast(String field, double lower) {
            return new Range(field, lower, Double.POSITIVE_INFINITY, true, false);
        }
    }

    /**
     * 长整数区间 —— 与 {@link Range} 并列，查的是 {@code LongField} 索引的字段。
     *
     * <p>为什么要有它：{@code Range}（double）只有 52 位尾数，**超过 2^53 的整数存不准**。
     * 纳秒时间戳、雪花 ID、大额精确整数必须走这一条，它没有任何精度损失。
     *
     * <p>两者**不能混用** —— 落盘编码不同，编译器会按字段类型拦下来。
     */
    record LongRange(String field, long lower, long upper,
                     boolean includeLower, boolean includeUpper) implements Condition {

        public LongRange {
            Objects.requireNonNull(field, "field");
            if (lower > upper) {
                throw new IllegalArgumentException("下界大于上界：" + lower + " > " + upper);
            }
        }

        /** {@code [lower, upper]} 双闭。 */
        public static LongRange closed(String field, long lower, long upper) {
            return new LongRange(field, lower, upper, true, true);
        }

        /** {@code (-∞, upper]} —— 单侧无界。 */
        public static LongRange atMost(String field, long upper) {
            return new LongRange(field, Long.MIN_VALUE, upper, true, true);
        }

        /** {@code [lower, +∞)} —— 单侧无界。 */
        public static LongRange atLeast(String field, long lower) {
            return new LongRange(field, lower, Long.MAX_VALUE, true, true);
        }
    }

    /**
     * 分组：把若干条件按 {@code relation} 组合起来。可嵌套。
     *
     * <p>这才是表达"条件之间 and/or"的地方 —— 例如
     * 「（标题或描述命中）**且**（价格在区间内）」就是把一个 {@link Text} 和一个
     * {@link Range} 塞进一个 {@code Group(MUST, …)}。
     */
    record Group(Occur relation, List<Condition> children) implements Condition {

        public Group {
            Objects.requireNonNull(relation, "relation");
            children = List.copyOf(children);
            if (children.isEmpty()) {
                throw new IllegalArgumentException("Group 至少要有一个子条件");
            }
        }

        public static Group and(Condition... children) {
            return new Group(Occur.MUST, List.of(children));
        }

        public static Group or(Condition... children) {
            return new Group(Occur.SHOULD, List.of(children));
        }
    }
}
