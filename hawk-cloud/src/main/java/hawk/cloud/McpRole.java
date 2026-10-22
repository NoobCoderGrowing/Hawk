package hawk.cloud;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * {@code HAWK_ROLE=mcp} 时，只装配 {@code hawk.cloud.mcp} 这一包。
 *
 * <p>MCP 进程**不碰索引与检索**：它只把 JSON-RPC 的 {@code tools/call} 翻成一次
 * {@code POST /search} 转发给协调器。分片扇出、多路融合、参数校验仍然只存在于
 * {@code hawk.cloud.coordinator} 一处 —— 所以那边一行都不用改。
 *
 * <p>用精确相等而不是 {@code endsWith}：mcp 是**单例角色**，不该被别的名字误匹配。
 *
 * <p>⚠️ 光有这个类不够：Spring AI 的 MCP server 自动配置是全局的，还要靠
 * {@code McpEnabledPostProcessor} 按 role 打开 {@code spring.ai.mcp.server.enabled}。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "hawk.cloud.role", havingValue = "mcp")
@ComponentScan("hawk.cloud.mcp")
public class McpRole {
}
