package hawk.vector.query;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 向量检索的**查询树** —— 与文本侧的 {@code hawk.recall.request.Condition} 是**两套独立的类型**，
 * 不是同一个东西的两个实现。
 *
 * <h2>为什么另起一套，而不是复用文本的 Condition</h2>
 * 同一个 sealed interface 被两个引擎解释，会让**同一个条件在两个引擎上含义不同**：
 * <ul>
 *   <li>文本侧的 {@code Range} 是精确的数值过滤；向量索引里根本没有数值索引，
 *       真要支持也只能"取前 K 再去筛"，结果取决于取了多少 —— 调用方看不出自己拿到的是哪种；</li>
 *   <li>文本侧的 {@code termRelation}（词间 MUST/SHOULD）在向量侧没有对应物：
 *       原文被编码成**一个向量**，不存在可以组合的"词"。</li>
 * </ul>
 * 与其在向量侧"尽力近似"，不如把能力边界写进类型里：**向量种树只有它真能精确表达的两个节点。**
 *
 * <h2>节点</h2>
 * <pre>
 *   Text(text, fields)          编码这段文本，在 fields 指定的字段图上搜；fields 为空 = 所有字段
 *   Group(relation, children)   AND（交集）/ OR（并集）
 * </pre>
 *
 * <h2>分数</h2>
 * 所有子句都过**同一个模型**，余弦分同量纲，所以直接相加 —— 不需要 RRF
 * （RRF 是用来规避 BM25 与余弦这类不可比分数的，见 {@code VectorSearcher} 的说明）。
 */
public sealed interface VectorCondition {

    /** 组内关系。 */
    enum Relation {
        /** 交集：文档必须出现在**每一个**子节点里。注意 ANN 的固有含义，见 {@link Group}。 */
        AND,
        /** 并集：文档出现在**任一**子节点里即可。 */
        OR
    }

    /**
     * 把这段文本编码成向量，在指定字段的图上检索。
     *
     * @param text   未分词原文 —— 由检索端用索引同一个模型编码
     * @param fields 要搜的字段；**空列表 = 所有向量字段**。
     *               非空时每个名字都必须在索引里有对应的图，否则抛 {@link IllegalArgumentException}
     *               （拼错一个字段名就静默变成"搜全部"是最难查的那类错）
     */
    record Text(String text, List<String> fields) implements VectorCondition {

        public Text {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("text 节点需要一个非空的 text");
            }
            fields = fields == null ? List.of() : List.copyOf(new LinkedHashSet<>(fields));
        }

        /** 没指定字段 = 搜全部。 */
        public boolean allFields() {
            return fields.isEmpty();
        }
    }

    /**
     * 多个子条件的 AND / OR。
     *
     * <h2>AND 是**近似**的 —— 这是 ANN 的固有性质，不是实现缺陷</h2>
     * 每个子节点只能返回它自己的**前 K 条**，所以求出来的交集是
     * "同时出现在每个子节点各自的前 K 条里"，**不是**"全局同时满足"。
     * 一篇文档可能真的和两个子句都最相似，但没进某个子句的前 K，就被漏掉了。
     *
     * <p>缓解手段是**过采样**（每个子节点多取一些再求交，见 {@code VectorSearcher}），
     * 但只能降低漏掉的概率，**不能消除**。所以含 AND 的查询在响应里
     * {@code totalExact} 一律为 {@code false}，调用方必须知道自己拿到的不是精确结果。
     */
    record Group(Relation relation, List<VectorCondition> children) implements VectorCondition {

        public Group {
            if (relation == null) {
                throw new IllegalArgumentException("group 需要 relation（AND / OR）");
            }
            if (children == null || children.isEmpty()) {
                throw new IllegalArgumentException("group 至少要有一个 child");
            }
            children = List.copyOf(children);
        }
    }
}
