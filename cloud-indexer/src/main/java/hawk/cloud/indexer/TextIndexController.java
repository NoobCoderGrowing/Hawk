package hawk.cloud.indexer;

import field.Field;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

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
 * <p>这里踩过两个坑，都已在 {@code core/field} 里修掉，记下来免得重犯：
 * <ul>
 *   <li><b>判别字段原先是 {@code Id.CLASS}</b>：请求里得写 {@code "field.StringField"} 这种全类名。
 *       既是对外契约上的反序列化 gadget 面，又和实现类各自的 {@code @JsonTypeInfo} 打架
 *       （{@code StringField} 声明 {@code @class}、{@code NumberField} 声明 {@code type}），
 *       同一个字段按静态类型不同会序列化出不同形态。</li>
 *   <li><b>字段原先声明成 {@code Enum<Field.Tokenized>}</b>，靠 {@code @JsonDeserialize(as = ...)}
 *       给 Jackson 提示。那个注解属于 {@code com.fasterxml.jackson.databind.annotation}，
 *       在 Jackson 3 里搬到了 {@code tools.jackson.databind.annotation}，而 Spring Boot 4 的
 *       HTTP 层用的正是 Jackson 3（{@code tools.jackson.databind:3.1.5}）—— 提示被静默忽略，
 *       类型擦除成 {@code java.lang.Enum}，报 "Cannot construct instance of java.lang.Enum"。
 *       编译期毫无迹象：Jackson 2 的注解 jar（{@code jackson-databind:2.21.5}）确实还在 classpath 上。</li>
 * </ul>
 */
@RestController
public class TextIndexController {

    private static final Logger log = LoggerFactory.getLogger(TextIndexController.class);

    /**
     * 请求体。
     *
     * @param shardCount 总分片数
     * @param fieldList  所有需要索引或者存储的 Field
     */
    public record TextIndexRequest(int shardCount, List<Field> fieldList) {
    }

    @PostMapping("/textIndex")
    public ResponseEntity<?> textIndex(@RequestBody TextIndexRequest request) {
        if (request.shardCount() < 1) {
            return badRequest("shardCount 必须 ≥ 1，收到 " + request.shardCount());
        }
        if (request.fieldList() == null || request.fieldList().isEmpty()) {
            return badRequest("fieldList 不能为空");
        }

        List<String> names = request.fieldList().stream()
                .map(field -> field == null ? "null" : field.getName())
                .toList();
        log.info("收到 textIndex 请求：shardCount={}，fieldList={}（各字段的 isTokenized/isStored 见下）",
                request.shardCount(), names);
        for (Field field : request.fieldList()) {
            if (field != null) {
                log.info("  field {}: tokenized={} stored={}",
                        field.getName(), field.isTokenized(), field.isStored());
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "accepted");
        body.put("shardCount", request.shardCount());
        body.put("fieldCount", request.fieldList().size());
        body.put("fields", names);
        body.put("note", "索引动作尚未实现：本接口目前只接收参数并回显");
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}
