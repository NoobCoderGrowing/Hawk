package hawk.cloud.indexer;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import field.Field;
import field.NumberField;
import field.PrimaryKeyField;
import field.StringField;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 建立倒排索引的接口：{@code POST /textIndex}。
 *
 * <h2>请求体</h2>
 * <pre>
 * {
 *   "shardCount": 3,          // 总分片数
 *   "fieldList": [ ... ]      // 所有需要索引或存储的 Field
 * }
 * </pre>
 * 见仓库根目录的 {@code textIndex.md}。
 *
 * <h2>目前是骨架：只接参数，不做索引</h2>
 * 这个端点不读数据源、不建索引、不落盘 —— 只校验参数、把收到的内容打进日志并回显。
 * 真正的索引动作尚未实现。
 *
 * <h2>fieldList 的 JSON 形态</h2>
 * <pre>
 * {
 *   "shardCount": 3,
 *   "fieldList": [
 *     {"type": "StringField",     "name": "title",    "isTokenized": "YES", "isStored": "YES"},
 *     {"type": "NumberField",     "name": "price",    "isTokenized": "NO",  "isStored": "YES"},
 *     {"type": "PrimaryKeyField", "name": "uniqueID", "isTokenized": "NO",  "isStored": "YES"}
 *   ]
 * }
 * </pre>
 * {@code type} 是**短名**（{@code Id.NAME} + 显式白名单），不是全类名。{@code value} 可带可不带 ——
 * 「字段定义」式的请求体本来就不带值。
 *
 * <h2>为什么多态要自己 switch</h2>
 * fastjson 对接口类型 {@code Field} 只会返回一个 JDK 代理（不是具体子类），
 * 而它的 autotype（{@code @type} 全类名）是典型的反序列化 gadget 面。所以这里按 {@code type}
 * 显式分发 —— 白名单就是下面那段 switch，{@code field} 包加新实现时要同步改这里。
 *
 * <h2>为什么还要自己校验枚举</h2>
 * fastjson 对认不出的枚举值**静默置 null**（Jackson 会报错）。而 {@code DocWriter} 判的是
 * {@code field.isTokenized() == Field.Tokenized.YES}，null 等于"不索引" ——
 * 一个拼错的 {@code "MAYBE"} 会静默导致该字段不被索引、不报任何错。所以下面显式校验。
 */
@RestController
public class TextIndexController {

    private static final Logger log = LoggerFactory.getLogger(TextIndexController.class);

    /**
     * {@code POST /textIndex}。
     *
     * <p>请求体按 fastjson 的 {@link JSONObject} 收，不另建请求 DTO —— 两个参数就地取出。
     *
     * <p>多态**不走框架**：fastjson 对接口类型 {@code Field} 只会回一个 JDK 代理（不是具体子类），
     * 而且它的 autotype（{@code @type} 全类名）本身就是反序列化 gadget 面。所以这里按 {@code type}
     * 显式 switch 到具体类 —— 白名单就在下面这段 switch 里，加新字段类型时改这里。
     */
    @PostMapping("/textIndex")
    public ResponseEntity<?> textIndex(@RequestBody JSONObject request) {
        Integer shardCount = request.getInteger("shardCount");
        if (shardCount == null) {
            return badRequest("缺少 shardCount（应为整数）");
        }
        if (shardCount < 1) {
            return badRequest("shardCount 必须 ≥ 1，收到 " + shardCount);
        }

        JSONArray rawList = request.getJSONArray("fieldList");
        if (rawList == null || rawList.isEmpty()) {
            return badRequest("fieldList 不能为空");
        }

        List<Field> fieldList = new ArrayList<>(rawList.size());
        for (int i = 0; i < rawList.size(); i++) {
            JSONObject item = rawList.getJSONObject(i);
            if (item == null) {
                return badRequest("fieldList[" + i + "] 不是对象");
            }
            String type = item.getString("type");

            // fastjson 对认不出的枚举值**静默置 null**（Jackson 会报错）。而 DocWriter 判的是
            // `field.isTokenized() == Field.Tokenized.YES` —— null 等于"不索引"，
            // 于是一个拼错的 "MAYBE" 会静默导致该字段不被索引。这里补上显式校验。
            String badEnum = invalidEnum(item, "isTokenized");
            if (badEnum == null) {
                badEnum = invalidEnum(item, "isStored");
            }
            if (badEnum != null) {
                return badRequest("fieldList[" + i + "] " + badEnum);
            }

            Field field;
            try {
                field = switch (type == null ? "" : type) {
                    case "StringField" -> item.toJavaObject(StringField.class);
                    case "NumberField" -> item.toJavaObject(NumberField.class);
                    case "PrimaryKeyField" -> item.toJavaObject(PrimaryKeyField.class);
                    default -> null;
                };
            } catch (RuntimeException e) {
                // 枚举值写错之类 —— 是客户端问题，给 400 而不是 500
                return badRequest("fieldList[" + i + "] 解析失败：" + e.getMessage());
            }
            if (field == null) {
                return badRequest("fieldList[" + i + "] 的 type 不在白名单内：" + type);
            }
            fieldList.add(field);
        }

        List<String> names = fieldList.stream().map(Field::getName).toList();
        log.info("收到 textIndex 请求：shardCount={}，fieldList={}（各字段的 isTokenized/isStored 见下）",
                shardCount, names);
        for (Field field : fieldList) {
            log.info("  field {}: tokenized={} stored={}",
                    field.getName(), field.isTokenized(), field.isStored());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "accepted");
        body.put("shardCount", shardCount);
        body.put("fieldCount", fieldList.size());
        body.put("fields", names);
        body.put("note", "索引动作尚未实现：本接口目前只接收参数并回显");
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    /**
     * 校验一个枚举字段的取值。
     *
     * @return 出错说明；{@code null} 表示没问题（键缺失也算没问题 —— 保持 null 由调用方决定语义）
     */
    private static String invalidEnum(JSONObject item, String key) {
        String value = item.getString(key);
        if (value == null || "YES".equals(value) || "NO".equals(value)) {
            return null;
        }
        return key + " 只能是 YES 或 NO，收到 " + value;
    }
}
