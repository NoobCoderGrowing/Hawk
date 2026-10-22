package hawk.cloud.mcp;

/**
 * MCP 工具**执行失败**（协调器返回 4xx/5xx、连不上、超时）。
 *
 * <p>它不是 JSON-RPC 协议错误：工具调到了、但执行不下去。Spring AI 会把从这里冒出去的
 * 异常包成 {@code isError=true} 的工具结果，模型能看到消息并自行决定下一步 ——
 * 这正是 MCP 规范里工具执行失败的正确上报方式。
 */
public class McpToolException extends RuntimeException {

    public McpToolException(String message) {
        super(message);
    }

    public McpToolException(String message, Throwable cause) {
        super(message, cause);
    }
}
