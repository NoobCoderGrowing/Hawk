package hawk.cloud.indexer;

import com.alibaba.fastjson.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 建立倒排索引的接口：{@code POST /textIndex}。
 *
 * <h2>请求体</h2>
 * 与 {@code /vectorIndex} **共用同一个信封**，这里只列本端点关心的键：
 * <pre>
 * {
 *   "shardCount": 3,          // 必填，总分片数
 *   "fieldList": [ ... ],     // 必填，所有需要索引或存储的 Field
 *   "round": 1                // 可选，协调器带；旁路调用不带
 * }
 * </pre>
 * 见仓库根目录的 {@code textIndex.md}，以及 {@link IndexApi} 里的完整契约。
 *
 * <h2>只接参数，不做索引</h2>
 * 端点只做**校验 + 提交**：真正的建索引在 {@link IndexService#startAsync} 的后台线程里跑，
 * 建完由它通知协调器。所以这里**立刻返回**，不在请求线程里扫语料。
 *
 * <p>本节点同时只建一份，抢不到锁立刻回 {@code text-indexing}。**这把锁与
 * {@code /vectorIndex} 那把是两把独立的锁** —— 两条工作负载互不相干，一方在跑不该把另一方也拒掉。
 *
 * <h2>解析与响应都在 {@link IndexApi}</h2>
 * {@code fieldList} 的 JSON 形态、多态为什么不走框架、枚举为什么自己校验、主键为什么必须恰好一条
 * —— 两个端点共用同一份实现，理由都写在 {@link IndexApi} 里。
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

    @PostMapping("/textIndex")
    public ResponseEntity<?> textIndex(@RequestBody JSONObject request) {
        IndexApi.Parsed parsed = IndexApi.parse(request);
        if (!parsed.ok()) {
            return parsed.error();
        }
        IndexApi.Request r = parsed.request();
        IndexApi.logRequest("textIndex", r);

        // 本节点同时只建一份倒排索引。抢不到锁立刻回，不排队、不阻塞 ——
        // 排队会让"什么时候轮到我"不可预测，调用方（协调器）宁可知道这个节点忙着。
        if (!indexService.startAsync(r.shardCount(), r.fieldList(), r.fieldTokenizers(), r.round())) {
            log.info("收到 textIndex 请求，但本节点正在建倒排索引，直接回 text-indexing");
            return IndexApi.busy("text-indexing", role, shardNum, "倒排");
        }

        log.info("已受理 textIndex 请求：shardCount={}，本字段集分词配置={}（后台开建）",
                r.shardCount(), r.fieldTokenizers());
        return ResponseEntity.accepted().body(IndexApi.acceptedBody(role, shardNum, "倒排"));
    }
}
