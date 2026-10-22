package hawk.cloud.recall;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import hawk.vector.query.VectorCondition;

import java.util.ArrayList;
import java.util.List;

/**
 * 把请求体里的 {@code query} 解成 {@link VectorCondition} 树。文本侧的对照物是 {@link ConditionJson}。
 *
 * <h2>为什么也是手写</h2>
 * 同样两条理由（见 {@link ConditionJson}）：{@link VectorCondition} 是 sealed interface + record，
 * fastjson 不支持 record；而靠 {@code @type} 全类名做多态是反序列化 gadget 面。
 *
 * <h2>线格式</h2>
 * <pre>
 *   "老黄冰糖"                                          // 字符串简写 = 下面的 text 节点
 *   {"type":"text",  "text":"老黄冰糖", "fields":["title"]}   // fields 可省 = 所有向量字段
 *   {"type":"group", "relation":"OR", "children":[ … ]}      // AND / OR
 * </pre>
 *
 * <p><b>只支持 text / group。</b> {@code range} / {@code longRange} 会**明确报错**而不是近似执行 ——
 * 向量索引里没有数值索引，真要做也只能"取前 K 再去筛"，结果取决于取了多少，
 * 调用方看不出自己拿到的是哪种（详见 {@link VectorCondition} 的类注释）。
 */
public final class VectorConditionJson {

    private static final String TYPE_TEXT = "text";
    private static final String TYPE_GROUP = "group";

    private VectorConditionJson() {
    }

    /**
     * 解析请求体里的 {@code query}。
     *
     * @param raw 字符串（text 节点简写）或 {@link JSONObject}（查询树）
     * @throws IllegalArgumentException 消息可直接回给客户端（HTTP 400）
     */
    public static VectorCondition parse(Object raw) {
        if (raw == null) {
            throw new IllegalArgumentException("缺少 query");
        }
        if (raw instanceof String text) {
            // 简写：与改动之前完全兼容 —— 那时 query 就是个字符串
            return new VectorCondition.Text(text, List.of());
        }
        if (!(raw instanceof JSONObject json)) {
            throw new IllegalArgumentException(
                    "query 必须是字符串（简写）或对象（查询树），收到 " + raw.getClass().getSimpleName());
        }
        return parseNode(json);
    }

    // ------------------------------------------------------------------

    private static VectorCondition parseNode(JSONObject json) {
        String type = json.getString("type");
        if (type == null) {
            throw new IllegalArgumentException("向量查询节点缺少 type（只能是 text / group）");
        }
        return switch (type) {
            case TYPE_TEXT -> parseText(json);
            case TYPE_GROUP -> parseGroup(json);
            case "range", "longRange" -> throw new IllegalArgumentException(
                    "向量检索不支持 " + type + "：向量索引里没有数值索引，做了也只能是近似过滤。"
                            + "要按数值筛选请走文本检索那一路。");
            // 白名单就是这里。认不出的类型必须报错 —— 静默放行会变成"匹配不到任何东西"的空查询
            default -> throw new IllegalArgumentException(
                    "不认识的 type：" + type + "（向量检索只支持 text / group）");
        };
    }

    private static VectorCondition parseText(JSONObject json) {
        // fields **可以不给** —— 不给就是所有向量字段。这与文本侧的 Condition.Text 不同
        // （那边必须指定字段，因为它要决定在哪几个倒排里查）。
        return new VectorCondition.Text(json.getString("text"), strings(json, "fields"));
    }

    private static VectorCondition parseGroup(JSONObject json) {
        VectorCondition.Relation relation = relation(json.getString("relation"));
        JSONArray rawChildren = json.getJSONArray("children");
        if (rawChildren == null || rawChildren.isEmpty()) {
            throw new IllegalArgumentException("group 至少要有一个 child");
        }
        List<VectorCondition> children = new ArrayList<>(rawChildren.size());
        for (int i = 0; i < rawChildren.size(); i++) {
            JSONObject child = rawChildren.getJSONObject(i);
            if (child == null) {
                throw new IllegalArgumentException("group 的 children[" + i + "] 不是对象");
            }
            try {
                children.add(parseNode(child));
            } catch (IllegalArgumentException e) {
                // 带上路径 —— 嵌套深了否则看不出是哪个节点错的
                throw new IllegalArgumentException("children[" + i + "]：" + e.getMessage());
            }
        }
        return new VectorCondition.Group(relation, children);
    }

    /**
     * 用 {@code AND} / {@code OR}，**不是** {@code Occur} —— 向量侧没有 FILTER / MUST_NOT 的对应物，
     * 沿用那套词表只会让人以为能写。
     */
    private static VectorCondition.Relation relation(String value) {
        if (value == null) {
            throw new IllegalArgumentException("group 缺少 relation（只能是 AND / OR）");
        }
        return switch (value) {
            case "AND", "and" -> VectorCondition.Relation.AND;
            case "OR", "or" -> VectorCondition.Relation.OR;
            default -> throw new IllegalArgumentException(
                    "relation 只能是 AND / OR，收到 " + value);
        };
    }

    private static List<String> strings(JSONObject json, String key) {
        JSONArray array = json.getJSONArray(key);
        if (array == null) {
            return List.of();
        }
        if (array.isEmpty()) {
            throw new IllegalArgumentException(
                    key + " 不能是空数组：要限定就至少给一个名字，不限定就把整个键去掉");
        }
        return array.toJavaList(String.class);
    }
}
