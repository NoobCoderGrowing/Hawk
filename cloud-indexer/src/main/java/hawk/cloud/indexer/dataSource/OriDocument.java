package hawk.cloud.indexer.dataSource;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据源读出来的**原始**文档 —— 还没有变成 {@code document.Document}、也还没有字段定义。
 *
 * <p>结构是两层 {@code Map}：
 * <pre>
 *   "title"    -> { "镀锌弯头" -> "String" }
 *   "price"    -> { 9.9       -> "Double" }
 *   "uniqueID" -> { 135L      -> "Long" }
 * </pre>
 * 外层 key 是字段名，内层是个**只有一个键值对**的 map：键是值本身，值是它的类型名。
 * 这样"值 + 类型"能一起带着走而不必再定义一个小类 —— 代价是取值要绕一层
 * （{@link #value(String)} / {@link #dataType(String)} 把这个绕法包起来了，别直接去操作内层 map）。
 *
 * <h2>值可以是 null</h2>
 * 数据源里空字段会记成 {@code 字段名 -> {null -> "String"}}。所以 {@link #value(String)}
 * 返回 null 有两种含义："没有这个字段"和"字段值为空"。要区分就用
 * {@link #hasField(String)}（或 {@code getFields().containsKey(name)}）。
 *
 * <h2>类型名目前只是带着走</h2>
 * 现在没有任何地方读 {@link #dataType(String)} —— 重建 {@code Document} 时用的是**值本身的
 * 运行时类型**（{@code Number} / {@code String}）加 fieldList 里的定义。类型名留着是为了
 * 以后能据它做类型校验或从字符串做转换（如 CSV 里一切皆字符串时）。
 */
public class OriDocument {

    /** 字段名 → 单键 map（键是值，值是类型名）。用 LinkedHashMap 保持字段顺序。 */
    private final Map<String, Map<Object, String>> fields = new LinkedHashMap<>();

    /** 记一个字段。{@code dataType} 是类型名，如 {@code "String"} / {@code "Double"} / {@code "Long"}。 */
    public void add(String fieldName, Object value, String dataType) {
        Map<Object, String> single = new LinkedHashMap<>(1);
        single.put(value, dataType);
        fields.put(fieldName, single);
    }

    public Map<String, Map<Object, String>> getFields() {
        return fields;
    }

    public boolean hasField(String fieldName) {
        return fields.containsKey(fieldName);
    }

    /** 某字段的值；没有该字段时为 null（注意字段存在但值为空时也是 null）。 */
    public Object value(String fieldName) {
        Map<Object, String> single = fields.get(fieldName);
        if (single == null || single.isEmpty()) {
            return null;
        }
        Iterator<Object> it = single.keySet().iterator();
        return it.next();
    }

    /** 某字段的类型名；没有该字段时为 null。 */
    public String dataType(String fieldName) {
        Map<Object, String> single = fields.get(fieldName);
        if (single == null || single.isEmpty()) {
            return null;
        }
        Iterator<String> it = single.values().iterator();
        return it.next();
    }

    @Override
    public String toString() {
        return "OriDocument" + fields;
    }
}
