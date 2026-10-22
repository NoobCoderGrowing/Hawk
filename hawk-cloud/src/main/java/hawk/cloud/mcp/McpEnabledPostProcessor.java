package hawk.cloud.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * 只在 {@code HAWK_ROLE=mcp} 时打开 MCP server。
 *
 * <h2>为什么需要它</h2>
 * Spring AI 的 {@code McpServerAutoConfiguration} 是**全局**的：只要 starter 在 classpath 上、
 * {@code spring.ai.mcp.server.enabled} 不是 false，它就会在每个角色里注册 {@code POST /mcp}。
 * 它不认 {@code hawk.cloud.role} —— 所以这里把「角色」翻译成「MCP server 开关」。
 *
 * <p>{@code hawk-cloud.yml} 里把 {@code enabled} 写成 false；这里对 mcp 角色
 * {@code addFirst} 覆盖成 true。用 {@link Ordered#LOWEST_PRECEDENCE} 保证晚于
 * {@code ConfigDataEnvironmentPostProcessor}，否则 yml 还没加载、读不到 {@code hawk.cloud.role}。
 *
 * <p>注册在 {@code META-INF/spring.factories}（它不是自动配置，不走 {@code .imports}）。
 */
public class McpEnabledPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String role;
        try {
            role = env.getProperty("hawk.cloud.role");
        } catch (IllegalArgumentException e) {
            // 没设 HAWK_ROLE，yml 里的 ${HAWK_ROLE} 解析不了。
            // 让启动流程按原样失败，别在这里抛一个和角色无关的异常把真正原因盖掉。
            return;
        }
        if ("mcp".equals(role)) {
            env.getPropertySources().addFirst(new MapPropertySource(
                    "hawkMcpRole",
                    Map.of("spring.ai.mcp.server.enabled", "true")));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
