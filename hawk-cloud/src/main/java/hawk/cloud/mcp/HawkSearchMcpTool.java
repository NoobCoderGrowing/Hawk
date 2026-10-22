package hawk.cloud.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对外唯一的 MCP 工具：{@code hawk_search}，参数与协调器的 {@code POST /search} 逐项对应。
 *
 * <h2>这里不做业务校验</h2>
 * 只把给的参数拼进 body，合法性交给协调器 —— 「两路至少给一个」「query 必须是对象」
 * 这些规则只在 {@code SearchController} 一处，不会出现 MCP 能过、HTTP 不能过的两套规则。
 * 协调器回 400 时由 {@link HawkSearchClient} 转成 {@link McpToolException}，
 * 最终表现为 {@code isError=true} 的工具结果。
 *
 * <h2>limit 在这里单独压一道</h2>
 * 默认上限 100，比协调器的 1000 小：工具结果会进**模型上下文**，1000 条 × 多个字段
 * 对模型没有意义，只会挤掉别的信息。协调器那边的上限保持不变。
 *
 * <p>查询树用 {@code Map<String,Object>} 接收，不建类型：与协调器「不认识查询语法」的
 * 原则一致，DSL 加条件类型时这里同样一行不用改。
 */
@Component
public class HawkSearchMcpTool {

    private final HawkSearchClient client;

    @Value("${hawk.cloud.mcp.default-limit:10}")
    private int defaultLimit;

    @Value("${hawk.cloud.mcp.max-limit:100}")
    private int maxLimit;

    public HawkSearchMcpTool(HawkSearchClient client) {
        this.client = client;
    }

    @McpTool(
            name = "hawk_search",
            description = """
                    在 Hawk 的商品索引上做检索。query（倒排 Condition 树）与
                    vectorQuery（向量查询树）至少给一个；两个都给时做多路召回 + RRF 融合。
                    例如：{"query":{"type":"text","text":"冰糖"}}，或
                    {"vectorQuery":{"type":"text","text":"老黄冰糖","fields":["title"]}}。
                    响应里的 failed 非空表示有分片没有返回，结果可能不完整。""",
            // 检索是只读的。不标的话注解默认是 destructiveHint=true / readOnlyHint=false，
            // 客户端会把它当破坏性操作、每次调用都要求用户确认。
            annotations = @McpTool.McpAnnotations(
                    title = "Hawk 检索",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public Map<String, Object> search(
            @McpToolParam(description = "倒排 Condition 树，原样转发给 text-recall",
                    required = false) Map<String, Object> query,
            @McpToolParam(description = "向量 VectorCondition 树，原样转发给 vector-recall",
                    required = false) Map<String, Object> vectorQuery,
            @McpToolParam(description = "返回条数（1-100），默认 10",
                    required = false) Integer limit,
            @McpToolParam(description = "返回哪些字段（不是检索字段）",
                    required = false) List<String> fields) {

        Map<String, Object> body = new LinkedHashMap<>();
        if (query != null) {
            body.put("query", query);
        }
        if (vectorQuery != null) {
            body.put("vectorQuery", vectorQuery);
        }
        body.put("limit", limit == null ? defaultLimit : Math.max(1, Math.min(limit, maxLimit)));
        if (fields != null && !fields.isEmpty()) {
            body.put("fields", fields);
        }
        return client.search(body);
    }
}
