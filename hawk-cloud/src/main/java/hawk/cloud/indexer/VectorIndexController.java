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
import java.util.List;
import java.util.Map;

/**
 * {@code POST /vectorIndex} —— 建**向量索引分片**。与 {@link TextIndexController} 的差别：
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
 * <h2>三、入参与响应和 {@code /textIndex} 逐字同形</h2>
 * 公共信封（{@code shardCount}/{@code fieldList}/{@code round}，以及三态响应体）由 {@link IndexApi}
 * 统一提供 —— 协调器因此可以用同一套 body 构造、同一套响应处理打任一个端点。
 * 本端点在此之上**只多认三个自己的键**：
 * <pre>
 * {
 *   "shardCount": 10,
 *   "fieldList": [
 *     {"type": "StringField",     "name": "title",    "isIndexed": "YES", "isStored": "YES"},
 *     {"type": "PrimaryKeyField", "name": "uniqueID", "isIndexed": "NO",  "isStored": "YES"}
 *   ],
 *   "vectorFields": ["title"],               // 必填：fieldList 的子集，且必须是可向量化的字段
 *   "modelDir": "model/bge-small-zh-v1.5",   // 可选：覆盖配置里的默认值
 *   "device": "auto"                         // 可选：auto / gpu / cpu
 * }
 * </pre>
 *
 * <h2>role 不在这里校验</h2>
 * 本服务同时挂着 {@code /textIndex} 和 {@code /vectorIndex}，**由协调器按注册中心里各节点的
 * role 决定把哪个请求发过来**（见 {@code DESIGN.md §1.1}）。端点自己判断 role 是多余的。
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
        IndexApi.Parsed parsed = IndexApi.parse(request);
        if (!parsed.ok()) {
            return parsed.error();
        }
        IndexApi.Request r = parsed.request();
        IndexApi.logRequest("vectorIndex", r);

        // tokenizers 是**倒排侧**的东西，本端点不消费它。静默忽略会让人以为配了就生效了 ——
        // 而且 fieldList 是两个端点共用的同一个 body，把倒排的配置原样发到这儿是很自然的事。
        // 所以显式拒掉，别让它"看起来成功了"。
        if (!r.fieldTokenizers().isEmpty()) {
            return IndexApi.badRequest(
                    "fieldList 里的 tokenizers 是倒排索引（/textIndex）的配置，向量索引不消费它。"
                            + "请把它从发给 /vectorIndex 的请求里去掉；两个端点共用同一个 body 时，"
                            + "由调用方按 role 决定带不带。");
        }

        // vectorFields 是本端点独有的参数，两条规则见 parseVectorFields
        List<String> vectorFields;
        try {
            vectorFields = parseVectorFields(request, r.fieldList());
        } catch (IllegalArgumentException e) {
            return IndexApi.badRequest(e.getMessage());
        }

        String modelDir = request.getString("modelDir");
        String device = request.getString("device");

        // 要**一并存原文**的字段 = fieldList 里 isStored=YES 的那些。
        // 这就是"向量检索也要能带回字段值"的来源 —— 向量图本身不存原文，
        // 得另存一份（stored.fdt/.fdx/.fdm）。没标 stored 的字段就不存。
        List<String> storedFields = r.fieldList().stream()
                .filter(f -> f.isStored() == Field.Stored.YES)
                .map(Field::getName)
                .toList();

        // 本节点同时只建一份向量索引。抢不到锁立刻回，不排队、不阻塞
        if (!vectorIndexService.startAsync(r.shardCount(), r.fieldList(), vectorFields,
                storedFields, modelDir, device, r.round())) {
            log.info("收到 vectorIndex 请求，但本节点正在建向量索引，直接回 vector-indexing");
            return IndexApi.busy("vector-indexing", role, shardNum, "向量");
        }

        log.info("已受理 vectorIndex 请求：shardCount={}，vectorFields={}，模型={}，设备={}（后台开建）",
                r.shardCount(), vectorFields, modelDir, device);
        return ResponseEntity.accepted().body(IndexApi.acceptedBody(role, shardNum, "向量"));
    }

    /**
     * 解析并校验本端点独有的 {@code vectorFields}。两条规则：
     * <ol>
     *   <li>要在 {@code fieldList} 里</li>
     *   <li>必须是 {@link StringField} —— 只有它是文本</li>
     * </ol>
     *
     * <p>为什么在入口拦：{@code VectorIndexWriter} 只对 {@code StringField} 取文本，其余一律
     * 返回 null → 该字段**静默不收**。这种"配错了却只丢字段"是最难查的，所以提前报出来。
     *
     * <h2>为什么不要求 {@code isIndexed=YES}</h2>
     * 曾经这里还有第三条规则「{@code isIndexed} 必须是 YES，否则没有建倒排、编码不出内容」——
     * <b>理由是错的</b>。编码从头到尾不碰倒排：
     * <pre>
     *   IndexService.rebuildDocument()  遍历 fieldList，逐个 toField() 装进 Document（不看 isIndexed）
     *   FieldType.fromRawValue()        new StringField(name, value, isIndexed, isStored)，不过滤
     *   VectorIndexWriter.textOf()      doc.getFieldMap().get(name).getValue()，从不问 isIndexed
     * </pre>
     * 所以一个「只存原文、不建倒排」的 {@code StringField} **完全可以编码**。
     *
     * <p>那条规则的实际效果是**禁止「只存 + 编码，不建倒排」这个完全合理的配置** ——
     * 想编码就不得不连带建一份用不上的倒排。已删除。
     *
     * @throws IllegalArgumentException 消息可直接回给客户端（HTTP 400）
     */
    private static List<String> parseVectorFields(JSONObject request, List<Field> fieldList) {
        JSONArray rawVectorFields = request.getJSONArray("vectorFields");
        if (rawVectorFields == null || rawVectorFields.isEmpty()) {
            throw new IllegalArgumentException("vectorFields 不能为空（它决定哪些字段会被编码成向量）");
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
                throw new IllegalArgumentException("vectorFields[" + i + "]「" + name + "」不在 fieldList 里。"
                        + "fieldList 是：" + byName.keySet());
            }
            if (!(def instanceof StringField)) {
                throw new IllegalArgumentException("vectorFields[" + i + "]「" + name + "」是 "
                        + def.getClass().getSimpleName() + "，只有 StringField 能被向量化");
            }
            vectorFields.add(name);
        }
        return vectorFields;
    }
}
