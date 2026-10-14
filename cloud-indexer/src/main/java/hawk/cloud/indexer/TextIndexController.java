package hawk.cloud.indexer;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import field.Field;
import field.FieldType;
import field.NumberField;
import field.PrimaryKeyField;
import field.StringField;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * 端点只做**校验 + 提交**：真正的建索引在 {@link IndexService#startAsync} 的后台线程里跑，
 * 建完由它通知协调器。所以这里**立刻返回**，不在请求线程里扫语料。
 *
 * <p>本节点同时只建一份，抢不到锁立刻回 {@code text-indexing}。**这把锁与
 * {@code /vectorIndex} 那把是两把独立的锁** —— 两条工作负载互不相干。
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

    private final IndexService indexService;

    @Value("${hawk.cloud.indexer.role}")
    private String role;

    @Value("${hawk.cloud.indexer.shard-num}")
    private String shardNum;

    public TextIndexController(IndexService indexService) {
        this.indexService = indexService;
    }

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

        List<Field> fieldList;
        try {
            fieldList = parseFieldList(rawList);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }

        List<String> names = fieldList.stream().map(Field::getName).toList();
        log.info("收到 textIndex 请求：shardCount={}，fieldList={}（各字段的 isTokenized/isStored 见下）",
                shardCount, names);
        for (Field field : fieldList) {
            log.info("  field {}: tokenized={} stored={}",
                    field.getName(), field.isTokenized(), field.isStored());
        }

        // 索引轮次：协调器发的请求带它，**直接调本端点时为 null**（旁路调用）。
        // 它只是跟着索引落盘、供协调器判"这一轮有没有人建成"，本端点不校验它 ——
        // 校验轮次是协调器的事（它才知道当前是第几轮）。
        Long round = request.getLong("round");

        // 本节点同时只建一份倒排索引。抢不到锁立刻回，不排队、不阻塞 ——
        // 排队会让"什么时候轮到我"不可预测，调用方（协调器）宁可知道这个节点忙着。
        if (!indexService.startAsync(shardCount, fieldList, round)) {
            log.info("收到 textIndex 请求，但本节点正在建倒排索引，直接回 text-indexing");
            return ResponseEntity.status(409).body(Map.of(
                    "status", "text-indexing",
                    "role", role,
                    "shardNum", shardNum,
                    "message", "本节点正在建倒排索引，稍后再试或换一个副本"));
        }

        log.info("已受理 textIndex 请求：shardCount={}（后台开建）", shardCount);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "accepted");
        body.put("role", role);
        body.put("shardNum", shardNum);
        body.put("note", "已在后台开始建倒排索引，完成后通知协调器");
        return ResponseEntity.accepted().body(body);
    }

    private static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    /**
     * 解析并校验请求体里的 {@code fieldList}。
     *
     * <p>抽成静态方法是因为 {@code /vectorIndex} 要用**同一套**校验 —— 白名单、枚举取值、
     * 必填项三样必须一致，抄第二遍迟早会漂移。{@code /vectorIndex} 在此之上还要额外校验
     * {@code vectorFields}（见 {@code VectorIndexController}）。
     *
     * @throws IllegalArgumentException 消息可直接回给客户端（HTTP 400）
     */
    static List<Field> parseFieldList(JSONArray rawList) {
        List<Field> fieldList = new ArrayList<>(rawList.size());
        for (int i = 0; i < rawList.size(); i++) {
            JSONObject item = rawList.getJSONObject(i);
            if (item == null) {
                throw new IllegalArgumentException("fieldList[" + i + "] 不是对象");
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
                throw new IllegalArgumentException("fieldList[" + i + "] " + badEnum);
            }

            Field field;
            try {
                // 白名单由 FieldType 统一维护 —— 加新字段类型只改那一个枚举
                FieldType fieldType = FieldType.byJsonName(type == null ? "" : type);
                field = fieldType == null ? null : item.toJavaObject(fieldType.fieldClass());
            } catch (RuntimeException e) {
                // 枚举值写错之类 —— 是客户端问题，给 400 而不是 500
                throw new IllegalArgumentException("fieldList[" + i + "] 解析失败：" + e.getMessage());
            }
            if (field == null) {
                throw new IllegalArgumentException("fieldList[" + i + "] 的 type 不在白名单内：" + type);
            }
            fieldList.add(field);
        }
        return fieldList;
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
