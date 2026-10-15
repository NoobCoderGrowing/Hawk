package hawk.cloud.indexer;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import field.Field;
import field.StringField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /vectorIndex} —— 建**向量索引分片**。与 {@link TextIndexController} 的两个差别：
 *
 * <h2>一、立刻返回，不在请求线程里建索引</h2>
 * 编码比建倒排慢约 100 倍（实测 CPU 202 条/秒，10 万篇 × 2 字段 ≈ 16 分钟）。
 * 同步阻塞会让那条 HTTP 连接挂 16 分钟 —— 网关早就超时了，而且超时后客户端重试
 * 还会**再建一遍**。所以这里只做校验 + 提交，真正的活在 {@link VectorIndexService}
 * 的后台线程里跑；建完由它通知协调器。
 *
 * <h2>二、本节点同时只建一份，抢不到锁立刻回 {@code vector-indexing}</h2>
 * 不排队：排队会让"什么时候轮到我"不可预测，调用方（协调器）宁可立刻知道这个节点忙着。
 *
 * <p><b>这把锁与 {@code /textIndex} 那把是两把独立的锁</b>（各自一个 {@code AtomicBoolean}）——
 * 两条工作负载互不相干，一方在跑不该把另一方也拒掉。
 *
 * <h2>role 不在这里校验</h2>
 * 本服务同时挂着 {@code /textIndex} 和 {@code /vectorIndex}，**由协调器按注册中心里各节点的
 * role 决定把哪个请求发过来**（见 {@code roleType.md}）。端点自己判断 role 是多余的。
 *
 * <h2>请求体</h2>
 * <pre>
 * {
 *   "shardCount": 10,
 *   "fieldList": [
 *     {"type": "StringField",     "name": "title",    "isTokenized": "YES", "isStored": "YES"},
 *     {"type": "PrimaryKeyField", "name": "uniqueID", "isTokenized": "NO",  "isStored": "YES"}
 *   ],
 *   "vectorFields": ["title"],          // fieldList 的子集，且必须是可向量化的字段
 *   "modelDir": "model/bge-small-zh-v1.5",   // 可选，覆盖配置里的默认值
 *   "device": "auto"                          // 可选：auto / gpu / cpu
 * }
 * </pre>
 */
@RestController
public class VectorIndexController {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexController.class);

    private final VectorIndexService vectorIndexService;

    @Value("${hawk.cloud.indexer.role}")
    private String role;

    @Value("${hawk.cloud.indexer.shard-num}")
    private String shardNum;

    public VectorIndexController(VectorIndexService vectorIndexService) {
        this.vectorIndexService = vectorIndexService;
    }

    @PostMapping("/vectorIndex")
    public ResponseEntity<?> vectorIndex(@RequestBody JSONObject request) {
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
            // 与 /textIndex 共用同一套校验（白名单、枚举取值）—— 抄第二遍迟早会漂移
            fieldList = TextIndexController.parseFieldList(rawList);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }

        // vectorFields：要在 fieldList 里，且必须能向量化。
        // VectorIndexWriter 只对 StringField 取文本，其余一律返回 null → 该字段**静默不收**。
        // 这种"配错了却只丢字段"是最难查的，所以在入口就拦下来。
        JSONArray rawVectorFields = request.getJSONArray("vectorFields");
        if (rawVectorFields == null || rawVectorFields.isEmpty()) {
            return badRequest("vectorFields 不能为空（它决定哪些字段会被编码成向量）");
        }
        Map<String, Field> byName = new HashMap<>();
        for (Field f : fieldList) {
            byName.put(f.getName(), f);
        }
        List<String> vectorFields = new ArrayList<>(rawVectorFields.size());
        for (int i = 0; i < rawVectorFields.size(); i++) {
            String name = rawVectorFields.getString(i);
            Field def = byName.get(name);
            if (def == null) {
                return badRequest("vectorFields[" + i + "]「" + name + "」不在 fieldList 里。"
                        + "fieldList 是：" + byName.keySet());
            }
            if (!(def instanceof StringField)) {
                return badRequest("vectorFields[" + i + "]「" + name + "」是 "
                        + def.getClass().getSimpleName() + "，只有 StringField 能被向量化");
            }
            if (def.isTokenized() != Field.Tokenized.YES) {
                return badRequest("vectorFields[" + i + "]「" + name + "」的 isTokenized 不是 YES，"
                        + "没有建倒排，编码不出内容");
            }
            vectorFields.add(name);
        }

        String modelDir = request.getString("modelDir");
        String device = request.getString("device");

        // 索引轮次：协调器发的请求带它，**直接调本端点时为 null**（旁路调用）。
        // 它只是跟着索引落盘、供协调器判"这一轮有没有人建成"，本端点不校验它。
        Long round = request.getLong("round");

        // ---- 到这里参数都没问题，尝试抢占"正在建索引"这个状态 ----
        // 要**一并存原文**的字段 = fieldList 里 isStored=YES 的那些。
        // 这就是"向量检索也要能带回字段值"的来源 —— 向量图本身不存原文，
        // 得另存一份（stored.fdt/.fdx/.fdm）。没标 stored 的字段就不存。
        List<String> storedFields = fieldList.stream()
                .filter(f -> f.isStored() == Field.Stored.YES)
                .map(Field::getName)
                .toList();

        boolean started = vectorIndexService.startAsync(
                shardCount, fieldList, vectorFields, storedFields, modelDir, device, round);
        if (!started) {
            // 抢不到锁：本节点已经在建。立刻回，不排队、不阻塞
            log.info("收到 vectorIndex 请求，但本节点正在建向量索引，直接回 vector-indexing");
            return ResponseEntity.status(409).body(Map.of(
                    "status", "vector-indexing",
                    "role", role,
                    "shardNum", shardNum,
                    "message", "本节点正在建向量索引，稍后再试或换一个副本"));
        }

        log.info("已受理 vectorIndex 请求：shardCount={}，vectorFields={}，模型={}，设备={}（后台开建）",
                shardCount, vectorFields, modelDir, device);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "accepted");
        body.put("role", role);
        body.put("shardNum", shardNum);
        body.put("vectorFields", vectorFields);
        body.put("note", "已在后台开始建向量索引，完成后通知协调器");
        return ResponseEntity.accepted().body(body);
    }

    private static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}
