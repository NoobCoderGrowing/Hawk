package hawk.cloud.indexer;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import field.Field;
import field.FieldType;
import field.PrimaryKeyField;
import field.StringField;
import hawk.segment.core.anlyzer.TokenizerRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /textIndex} 与 {@code /vectorIndex} 的**公共信封** —— 请求解析与响应体构造。
 *
 * <h2>为什么要抽出来</h2>
 * 两个端点是同一条链路的两个变体：同样的 {@code shardCount}/{@code fieldList}/{@code round} 入参，
 * 同样的「202 受理 / 409 忙 / 400 参数错」三态，同样的「后台建完登记、召回端自己来拉」语义。
 * 原先两个 controller 各写一份逐字重复的骨架 —— 靠拷贝维持的一致随时会漂。
 * 收在这里之后，一致性是**结构上无法违反**的：改一处，两个端点同时改。
 *
 * <h2>统一后的契约</h2>
 * <pre>
 * 请求（两个端点共用同一个信封）：
 * {
 *   "shardCount": 3,               // 必填
 *   "fieldList": [ ... ],          // 必填，形状见 parseFieldList
 *                                  //   每个 StringField 可再带 "tokenizers": ["nshortest-1", ...]
 *                                  //   见 parseFieldTokenizers
 *   "round": 1,                    // 可选：协调器带，旁路调用不带
 *   "vectorFields": ["title"],     // 可选：仅 /vectorIndex 用，/textIndex 忽略
 *   "modelDir": "...",             // 可选：仅 /vectorIndex 用
 *   "device": "cpu"                // 可选：仅 /vectorIndex 用
 * }
 * 两个端点**只取自己认识的键，未知键一律忽略** —— 所以同一个 body 发给任一端点都合法，
 * 协调器因此不需要两套 body 构造代码（这条性质也写在 INDEX_BUILD_DISPATCH_DESIGN.md）。
 *
 * 响应（两个端点逐键同形）：
 *   202 {"status":"accepted","role":…,"shardNum":…,"note":…}
 *   409 {"status":"…-indexing","role":…,"shardNum":…,"message":…}
 *   400 {"message":"…"}
 * </pre>
 *
 * <h2>多态为什么不走框架</h2>
 * fastjson 对接口类型 {@code Field} 只会返回一个 JDK 代理（不是具体子类），
 * 而它的 autotype（{@code @type} 全类名）是典型的反序列化 gadget 面。所以按 {@code type}
 * 显式分发 —— 白名单由 {@link FieldType} 统一维护，加新字段类型只改那一个枚举。
 *
 * <h2>枚举为什么自己校验</h2>
 * fastjson 对认不出的枚举值**静默置 null**（Jackson 会报错）。而 {@code DocWriter} 判的是
 * {@code field.isIndexed() == Field.Indexed.YES}，null 等于"不索引" ——
 * 一个拼错的 {@code "MAYBE"} 会静默导致该字段不被索引、不报任何错。所以显式校验。
 */
final class IndexApi {

    private static final Logger log = LoggerFactory.getLogger(IndexApi.class);

    private IndexApi() {
    }

    // ------------------------------------------------------------------ 请求

    /**
     * 两个端点共同的入参。到这里 {@code fieldList} 已解析、主键已校验、每字段的 tokenizer 已校验。
     *
     * <p>类型专属的键（{@code vectorFields}/{@code modelDir}/{@code device}）**不在这里** ——
     * 它们各自从原始 {@link JSONObject} 里取，公共信封不认识它们。
     *
     * @param fieldTokenizers 字段名 → 该字段要用的 tokenizer 名。**只含显式配了 {@code tokenizers}
     *                        的字段**；没配的字段不在 map 里，由 {@code IndexConfig} 的默认值兜底。
     */
    record Request(int shardCount, List<Field> fieldList, Map<String, List<String>> fieldTokenizers,
                   Long round) {
    }

    /**
     * 解析结果：{@code request} 与 {@code error} 恰有一个非 null。
     *
     * <p>不用异常传递"参数错"：那是**预期内**的调用方错误（要回 400），不是异常路径，
     * 而且要把可直接回给客户端的消息带出来。
     */
    record Parsed(Request request, ResponseEntity<?> error) {

        static Parsed ok(Request request) {
            return new Parsed(request, null);
        }

        static Parsed fail(String message) {
            return new Parsed(null, badRequest(message));
        }

        boolean ok() {
            return error == null;
        }
    }

    /**
     * 校验并解析两个端点的公共入参。
     *
     * <p>顺序有讲究：先 {@code shardCount}（最便宜、最常错），再 {@code fieldList} 的形状，
     * 再主键 —— 每步失败都给出**这一条**的具体原因，不要攒到最后一起报。
     *
     * <p>轮次不在这里校验：它只是跟着索引落盘、供协调器判"这一轮有没有人建成"，
     * 校验轮次是协调器的事（它才知道当前是第几轮）。
     */
    static Parsed parse(JSONObject body) {
        Integer shardCount = body.getInteger("shardCount");
        if (shardCount == null) {
            return Parsed.fail("缺少 shardCount（应为整数）");
        }
        if (shardCount < 1) {
            return Parsed.fail("shardCount 必须 ≥ 1，收到 " + shardCount);
        }

        JSONArray rawList = body.getJSONArray("fieldList");
        if (rawList == null || rawList.isEmpty()) {
            return Parsed.fail("fieldList 不能为空");
        }

        List<Field> fieldList;
        Map<String, List<String>> fieldTokenizers;
        try {
            fieldList = parseFieldList(rawList);
            requireSinglePrimaryKey(fieldList);
            fieldTokenizers = parseFieldTokenizers(rawList, fieldList);
        } catch (IllegalArgumentException e) {
            return Parsed.fail(e.getMessage());
        }

        return Parsed.ok(new Request(shardCount, fieldList, fieldTokenizers, body.getLong("round")));
    }

    /**
     * 解析并校验每个字段的 {@code tokenizers} 键（**可选**：没写就交给默认值）。
     *
     * <p>为什么另起一趟遍历、而不是塞进 {@link #parseFieldList}：那一趟做的是**字段形状**校验
     * （白名单、旧键名、枚举取值），注释很密、逻辑很微妙；而 tokenizer 与字段形状正交。
     * 两趟各自只干一件事，改一个不会碰另一个。
     *
     * <h2>为什么必须显式读这个键</h2>
     * fastjson 对**未知键静默忽略**（同一个理由让 {@code isTokenized} 改名时加了防呆）。
     * 不读它、不校验它，一个拼错的 tokenizer 名就会退化成"用默认的分词器" ——
     * 建出来的索引和预期不一致，而没有任何报错。
     *
     * @return 只有**显式配了** tokenizers 的字段才在返回的 map 里
     * @throws IllegalArgumentException 消息可直接回给客户端（HTTP 400）
     */
    static Map<String, List<String>> parseFieldTokenizers(JSONArray rawList, List<Field> fieldList) {
        Map<String, Field> byName = new HashMap<>();
        for (Field f : fieldList) {
            byName.put(f.getName(), f);
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (int i = 0; i < rawList.size(); i++) {
            JSONObject item = rawList.getJSONObject(i);
            Object rawTokenizers = item.get("tokenizers");
            if (rawTokenizers == null) {
                continue;   // 没配 —— 由 IndexConfig 的默认值兜底
            }
            String fieldName = item.getString("name");
            if (!(rawTokenizers instanceof JSONArray raw)) {
                throw new IllegalArgumentException("fieldList[" + i + "]「" + fieldName
                        + "」的 tokenizers 必须是数组");
            }
            // 只有 StringField 会被分词（见 DocWriter.processIndexedField 的分支），
            // 给数值字段配 tokenizer 是配错了 —— 静默忽略会让人以为生效了
            if (!(byName.get(fieldName) instanceof StringField)) {
                throw new IllegalArgumentException("fieldList[" + i + "]「" + fieldName
                        + "」不是 StringField，只有文本字段会被分词，不能配 tokenizers");
            }
            if (raw.isEmpty()) {
                throw new IllegalArgumentException("fieldList[" + i + "]「" + fieldName
                        + "」的 tokenizers 不能是空数组：要分词就至少给一个名字，不配就把整个键去掉");
            }
            List<String> names = new ArrayList<>(raw.size());
            for (int j = 0; j < raw.size(); j++) {
                String name = raw.getString(j);
                if (!TokenizerRegistry.isKnown(name)) {
                    throw new IllegalArgumentException("fieldList[" + i + "]「" + fieldName
                            + "」的 tokenizers[" + j + "] 认不出：" + name
                            + "。可接受的形式：" + TokenizerRegistry.acceptedForms());
                }
                names.add(name);
            }
            result.put(fieldName, names);
        }
        return result;
    }

    /**
     * 解析并校验请求体里的 {@code fieldList}。
     *
     * <p>两个端点共用同一套校验 —— 白名单、枚举取值、必填项三样必须一致，
     * 抄第二遍迟早会漂。{@code /vectorIndex} 在此之上**额外**校验 {@code vectorFields}
     * （见 {@link VectorIndexController}）。
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

            // isTokenized 是旧键名（改名为 isIndexed）。不拦的话它会退化成"未知键"被静默忽略 ——
            // fastjson 认不出就置 null，而 null 等于"不索引"，于是老调用方发来的字段会
            // **悄悄失去索引**且不报任何错。宁可 400。
            if (item.containsKey("isTokenized")) {
                throw new IllegalArgumentException(
                        "fieldList[" + i + "] 的键 isTokenized 已改名为 isIndexed，请更新请求体");
            }

            // fastjson 对认不出的枚举值**静默置 null**（Jackson 会报错）。而 DocWriter 判的是
            // `field.isIndexed() == Field.Indexed.YES` —— null 等于"不索引"，
            // 于是一个拼错的 "MAYBE" 会静默导致该字段不被索引。这里补上显式校验。
            String badEnum = invalidEnum(item, "isIndexed");
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

    /**
     * 校验 {@code fieldList} 里有**且只有一条** {@link PrimaryKeyField}。
     *
     * <h2>为什么必须在入口拦</h2>
     * 主键决定文档归属哪个分片（{@code floorMod(pk, shardCount)}），没有它整轮索引无从谈起。
     * 而两个 Service 里的处理是**回 202 之后在后台 log.error + return** —— 调用方（协调器）
     * 收到的是"已受理"，然后那一轮**永远等不到 ready**，表现为静默不收敛。
     * 与其派发一个注定失败的轮次，不如在入口回 400：协调器收到 400 会中止整轮，
     * 并把这里的原话带给调用方。
     *
     * <h2>为什么是"恰好一条"而不是"至少一条"</h2>
     * {@link IndexService#primaryKeyFieldName} 取的是**第一条**，多余的定义被静默忽略 ——
     * 重复声明（比如同时写了 {@code uniqueID} 和 {@code id}）会让人以为生效的是后者。
     * 多写一条通常是笔误，报出来比悄悄猜一条强。
     *
     * @throws IllegalArgumentException 消息可直接回给客户端（HTTP 400）
     */
    static void requireSinglePrimaryKey(List<Field> fieldList) {
        int count = 0;
        for (Field def : fieldList) {
            if (def instanceof PrimaryKeyField) {
                count++;
            }
        }
        if (count == 1) {
            return;
        }
        String names = fieldList.stream().map(Field::getName).toList().toString();
        if (count == 0) {
            throw new IllegalArgumentException(
                    "fieldList 里必须有一条 PrimaryKeyField（用来算文档归属哪个分片），当前一条都没有。字段：" + names);
        }
        throw new IllegalArgumentException(
                "fieldList 里有 " + count + " 条 PrimaryKeyField，只允许一条（多余的会被静默忽略）。字段：" + names);
    }

    // ------------------------------------------------------------------ 响应

    /** 400：参数错。消息可直接回给客户端。 */
    static ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    /**
     * 409：本节点正忙（同一时刻只建一份，且**不排队** —— 排队会让"什么时候轮到我"不可预测，
     * 调用方宁可知道这个节点忙着，自己换个时间或换个副本再来）。
     *
     * @param status {@code text-indexing} / {@code vector-indexing}
     * @param what   消息里的说法，如"倒排"、"向量"
     */
    static ResponseEntity<?> busy(String status, String role, String shardNum, String what) {
        return ResponseEntity.status(409).body(Map.of(
                "status", status,
                "role", role,
                "shardNum", shardNum,
                "message", "本节点正在建" + what + "索引，稍后再试或换一个副本"));
    }

    /**
     * 202 的 body。两个端点**逐键同形** —— 类型专属信息（如 {@code vectorFields}）不放进来，
     * 否则调用方就得按端点分支解析响应体。那点信息已经打在索引器的日志里了。
     *
     * @param what 消息里的说法，如"倒排"、"向量"
     */
    static Map<String, Object> acceptedBody(String role, String shardNum, String what) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "accepted");
        body.put("role", role);
        body.put("shardNum", shardNum);
        body.put("note", "已在后台开始建" + what + "索引，完成后通知协调器");
        return body;
    }

    /**
     * 两个端点共用的入参日志：一行请求摘要 + 逐字段的 {@code isIndexed/isStored}。
     *
     * <p>逐字段那几行是**排错主力**：字段被静默不索引时，第一个要看的就是这里。
     */
    static void logRequest(String endpoint, Request request) {
        log.info("收到 {} 请求：shardCount={}，fieldList={}（各字段的 isIndexed/isStored 见下）",
                endpoint, request.shardCount(),
                request.fieldList().stream().map(Field::getName).toList());
        for (Field field : request.fieldList()) {
            log.info("  field {}: indexed={} stored={}",
                    field.getName(), field.isIndexed(), field.isStored());
        }
    }
}
