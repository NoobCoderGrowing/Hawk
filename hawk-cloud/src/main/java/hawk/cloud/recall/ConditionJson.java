package hawk.cloud.recall;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import hawk.recall.query.Occur;
import hawk.recall.request.Condition;

import java.util.ArrayList;
import java.util.List;

/**
 * 把检索请求的 JSON 解成 {@link Condition}。方案见 {@code DESIGN.md} §5.7.4。
 *
 * <h2>为什么必须手写，不能靠 fastjson</h2>
 * 两条，都是本仓库踩过的坑：
 * <ol>
 *   <li>{@link Condition} 是 sealed interface + <b>record</b>，而 fastjson <b>不支持 record</b>
 *       —— 会静默序列化成 {@code {}}；</li>
 *   <li>就算支持，靠 {@code @type} 全类名做多态是典型的**反序列化 gadget 面**。</li>
 * </ol>
 * 所以按 {@code type} 显式分发，白名单就是下面那个 switch。索引器的
 * {@code IndexApi#parseFieldList} 为同样的理由也是手写的。
 *
 * <h2>只做 JSON → Condition，反向不做</h2>
 * 协调器**原样转发**请求体、不认识查询语法，所以没有任何地方需要把 {@code Condition} 渲染回 JSON。
 * 没有需求的代码不写。
 *
 * <h2>线格式</h2>
 * <pre>
 *   {"type":"text",     "text":"冰糖", "fields":["title"],
 *                       "fieldRelation":"SHOULD", "termRelation":"MUST"}
 *   {"type":"range",    "field":"price", "lower":10, "upper":100,
 *                       "includeLower":true, "includeUpper":true}
 *   {"type":"longRange","field":"id",    "lower":1,  "upper":9,   …}
 *   {"type":"group",    "relation":"MUST", "children":[ … ]}
 * </pre>
 * 关系一律用 {@link Occur} 的**原名**（{@code MUST} / {@code SHOULD} / {@code FILTER} / {@code MUST_NOT}）——
 * 与代码里同一个词表，不做 AND/OR 之类的二次映射（多一层映射就多一处会漂移的地方）。
 */
public final class ConditionJson {

    private ConditionJson() {
    }

    /** 节点类型名 → 解析。白名单。 */
    private static final String TYPE_TEXT = "text";
    private static final String TYPE_RANGE = "range";
    private static final String TYPE_LONG_RANGE = "longRange";
    private static final String TYPE_GROUP = "group";

    /**
     * 解析一个条件节点。递归。
     *
     * @throws IllegalArgumentException 消息可直接回给客户端（HTTP 400）
     */
    public static Condition parse(JSONObject json) {
        if (json == null) {
            throw new IllegalArgumentException("条件不能是 null");
        }
        String type = json.getString("type");
        if (type == null) {
            throw new IllegalArgumentException("条件缺少 type（只能是 text / range / longRange / group）");
        }
        return switch (type) {
            case TYPE_TEXT -> parseText(json);
            case TYPE_RANGE -> parseRange(json, false);
            case TYPE_LONG_RANGE -> parseRange(json, true);
            case TYPE_GROUP -> parseGroup(json);
            // 白名单就是这里。别加 default 放行 —— 认不出的类型必须报错，
            // 不然拼错一个 type 会变成一个"匹配不到任何东西"的空查询
            default -> throw new IllegalArgumentException(
                    "不认识的 type：" + type + "（只能是 text / range / longRange / group）");
        };
    }

    // ------------------------------------------------------------------

    private static Condition parseText(JSONObject json) {
        String text = json.getString("text");
        if (text == null) {
            throw new IllegalArgumentException("text 条件缺少 text");
        }
        List<String> fields = strings(json, "fields");
        if (fields.isEmpty()) {
            // Condition.Text 自己也会拦，但这里的消息能指明是哪个节点
            throw new IllegalArgumentException("text 条件至少要指定一个 field");
        }
        // 这两个**不给默认值**：MUST 和 SHOULD 的差别是"全中"和"中一个"，
        // 猜错的结果是结果集不对，而且**不报任何错**。宁可让调用方多写两个词。
        return new Condition.Text(text, fields, occur(json, "fieldRelation"), occur(json, "termRelation"));
    }

    /**
     * {@link Condition.Range}（double）与 {@link Condition.LongRange}（long）共用。
     *
     * <p>上下界可以**省略一个或两个**，省略的那一侧按无界处理 ——
     * 于是"价格 ≤ 50"写成 {@code {"type":"range","field":"price","upper":50}} 就行，
     * 不用去写 {@code -Infinity}。省略的那一侧 {@code include} 恒为 false
     * （无界的那一侧没法"包含"），与 {@code Range.atMost/atLeast} 的约定一致。
     */
    private static Condition parseRange(JSONObject json, boolean asLong) {
        String field = json.getString("field");
        if (field == null) {
            throw new IllegalArgumentException((asLong ? "longRange" : "range") + " 条件缺少 field");
        }
        Double lowerBoxed = json.getDouble("lower");
        Double upperBoxed = json.getDouble("upper");

        if (asLong) {
            long lower = lowerBoxed == null ? Long.MIN_VALUE : lowerBoxed.longValue();
            long upper = upperBoxed == null ? Long.MAX_VALUE : upperBoxed.longValue();
            boolean incLower = lowerBoxed != null && bool(json, "includeLower", true);
            boolean incUpper = upperBoxed != null && bool(json, "includeUpper", true);
            try {
                return new Condition.LongRange(field, lower, upper, incLower, incUpper);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("longRange 的 " + e.getMessage());
            }
        }
        double lower = lowerBoxed == null ? Double.NEGATIVE_INFINITY : lowerBoxed;
        double upper = upperBoxed == null ? Double.POSITIVE_INFINITY : upperBoxed;
        boolean incLower = lowerBoxed != null && bool(json, "includeLower", true);
        boolean incUpper = upperBoxed != null && bool(json, "includeUpper", true);
        try {
            return new Condition.Range(field, lower, upper, incLower, incUpper);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("range 的 " + e.getMessage());
        }
    }

    private static Condition parseGroup(JSONObject json) {
        Occur relation = occur(json, "relation");
        JSONArray rawChildren = json.getJSONArray("children");
        if (rawChildren == null || rawChildren.isEmpty()) {
            throw new IllegalArgumentException("group 至少要有一个 child");
        }
        List<Condition> children = new ArrayList<>(rawChildren.size());
        for (int i = 0; i < rawChildren.size(); i++) {
            JSONObject child = rawChildren.getJSONObject(i);
            if (child == null) {
                throw new IllegalArgumentException("group 的 children[" + i + "] 不是对象");
            }
            try {
                children.add(parse(child));
            } catch (IllegalArgumentException e) {
                // 带上路径，否则嵌套深了根本看不出是哪一个节点错的
                throw new IllegalArgumentException("children[" + i + "]：" + e.getMessage());
            }
        }
        return new Condition.Group(relation, children);
    }

    // ------------------------------------------------------------------

    /**
     * 解析关系。
     *
     * <p><b>不用 {@code Occur.valueOf} 也不交给 fastjson</b>：后者对认不出的枚举值
     * **静默置 null**（本仓库的注释里专门记过这个坑），而 null 关系会让整个条件静默失效。
     * 这里显式白名单 + 报错。
     */
    private static Occur occur(JSONObject json, String key) {
        String value = json.getString(key);
        if (value == null) {
            throw new IllegalArgumentException("缺少 " + key + "（只能是 MUST / SHOULD / FILTER / MUST_NOT）");
        }
        return switch (value) {
            case "MUST" -> Occur.MUST;
            case "SHOULD" -> Occur.SHOULD;
            case "FILTER" -> Occur.FILTER;
            case "MUST_NOT" -> Occur.MUST_NOT;
            default -> throw new IllegalArgumentException(
                    key + " 只能是 MUST / SHOULD / FILTER / MUST_NOT，收到 " + value);
        };
    }

    private static List<String> strings(JSONObject json, String key) {
        JSONArray array = json.getJSONArray(key);
        if (array == null) {
            return List.of();
        }
        return array.toJavaList(String.class);
    }

    private static boolean bool(JSONObject json, String key, boolean defaultValue) {
        Boolean value = json.getBoolean(key);      // 认不出的值会抛，不会静默变成 false
        return value == null ? defaultValue : value;
    }
}
