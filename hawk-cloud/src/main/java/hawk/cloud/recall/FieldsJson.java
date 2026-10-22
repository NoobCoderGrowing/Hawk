package hawk.cloud.recall;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import document.Document;
import field.Field;
import field.FieldType;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 检索结果里 {@code fields} 的那点事 —— 两个 role 的检索端点共用。
 *
 * <p>抽出来是因为 <b>text-recall 与 vector-recall 都要做同一件事</b>：
 * 解析请求里的 {@code fields} 白名单，再把文档的存储字段渲染成 JSON。
 * 各写一份的话，"请求里 {@code fields} 传空数组算全要还是全不要"这种语义迟早会在两边分叉。
 */
final class FieldsJson {

    private FieldsJson() {
    }

    /**
     * 解析请求里的 {@code fields}。
     *
     * @return 要哪几个字段；**没传或传空数组 → {@code null}（= 全要）**。
     *         这是刻意的：省略参数是最常见的用法，而"传个空数组"在调用方那边几乎总是
     *         手滑，不是"我什么都不要"
     */
    static Set<String> resolve(JSONObject request) {
        JSONArray array = request.getJSONArray("fields");
        if (array == null || array.isEmpty()) {
            return null;
        }
        return new LinkedHashSet<>(array.toJavaList(String.class));
    }

    /**
     * 把文档的存储字段渲染成可以出 JSON 的值。
     *
     * <p>值的类型分发走 {@link FieldType#toJsonValue} —— 别在这里写 {@code instanceof} 链，
     * 那会给"字段类型分发"多开一个加新类型时会漏掉的地方。
     *
     * @param doc    文档；{@code null}（pk 不在这份索引里）→ 空 map
     * @param wanted 只这些字段；{@code null} = 全要
     */
    static Map<String, Object> render(Document doc, Set<String> wanted) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (doc == null) {
            return out;
        }
        for (Map.Entry<String, Field> entry : doc.getFieldMap().entrySet()) {
            if (wanted != null && !wanted.contains(entry.getKey())) {
                continue;
            }
            Field field = entry.getValue();
            out.put(entry.getKey(), FieldType.of(field).toJsonValue(field));
        }
        return out;
    }
}
