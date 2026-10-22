package hawk.cloud.mcp;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把 MCP 工具调用转发成协调器的 {@code POST /search}。
 *
 * <h2>地址从哪来</h2>
 * 每次调用都向 {@link CoordinatorLocator} 取当前候选（注册中心发现，或静态覆盖）。
 * 用轮询游标在多个协调器副本间分摊；某个地址连不上就换下一个（最多试两个）。
 *
 * <h2>错误映射</h2>
 * <ul>
 *   <li>协调器 <b>400</b>（参数不合法）→ 抛 {@link McpToolException}，消息取响应里的
 *       {@code message}。**不换地址重试**：换一个副本也是同样的拒绝。</li>
 *   <li>连不上 / 超时 / 5xx → 换下一个候选；都失败才抛，消息带地址，便于运维排查。</li>
 * </ul>
 * 一律抛异常、不在这里吞成正常结果：工具执行失败必须让客户端看到 {@code isError=true}，
 * 否则模型会把「没查到」当成「确实没有」。
 */
@Component
public class HawkSearchClient {

    private static final Logger log = LoggerFactory.getLogger(HawkSearchClient.class);

    private final CoordinatorLocator locator;
    private final HttpClient http;
    private final Duration readTimeout;

    /** 多副本之间的轮询游标。 */
    private final AtomicInteger cursor = new AtomicInteger();

    public HawkSearchClient(
            CoordinatorLocator locator,
            @Value("${hawk.cloud.mcp.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${hawk.cloud.mcp.read-timeout-ms:7000}") long readTimeoutMs) {

        this.locator = locator;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        this.readTimeout = Duration.ofMillis(readTimeoutMs);
    }

    public Map<String, Object> search(Map<String, Object> body) {
        List<String> bases = locator.candidates();
        if (bases.isEmpty()) {
            throw new McpToolException("协调器地址尚未发现：注册中心里还没有在线的 cloud/coordinator"
                    + "（请确认 coordinator 已启动并完成登记）");
        }

        int start = Math.floorMod(cursor.getAndIncrement(), bases.size());
        // 最多试两个：一个副本不行换下一个；不做无限重试，避免一个慢集群拖住整个工具调用
        int attempts = Math.min(bases.size(), 2);
        McpToolException last = null;
        for (int i = 0; i < attempts; i++) {
            String base = bases.get((start + i) % bases.size());
            try {
                return searchOnce(base, body);
            } catch (Rejected e) {
                // 400 是请求本身的问题，换副本结果一样 —— 直接交给模型
                throw e;
            } catch (McpToolException e) {
                last = e;
                log.warn("调用协调器 {} 失败，换下一个候选：{}", base, e.getMessage());
            }
        }
        throw last != null ? last : new McpToolException("调用协调器失败");
    }

    /** 向一个确定的协调器地址发一次检索请求。 */
    private Map<String, Object> searchOnce(String base, Map<String, Object> body) {
        String url = base + "/search";

        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(readTimeout)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            JSON.toJSONString(body), StandardCharsets.UTF_8))
                    .build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("调用协调器被中断：{}", url, e);
            // **不挂 cause**：Spring AI 上报工具错误时用的是最底层 cause 的 message，
            // 而 ConnectException.getMessage() 常常是 null —— 挂上 cause 会让模型看到 "null"。
            throw new McpToolException("调用协调器被中断：" + url);
        } catch (Exception e) {
            // ConnectException 的 getMessage() 常常是 null —— 别把 "null" 透给模型
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("调用协调器失败：{}", url, e);
            throw new McpToolException("调用协调器失败（" + url + "）：" + reason);
        }

        int status = response.statusCode();
        if (status == 200) {
            JSONObject parsed = JSON.parseObject(response.body());
            return new LinkedHashMap<>(parsed == null ? Map.of() : parsed);
        }
        if (status == 400) {
            // 协调器的 400 是调用方参数问题，把原文提示原样交给模型
            throw new Rejected(messageOf(response.body()));
        }
        log.warn("协调器 {} 返回 HTTP {}：{}", url, status, abbreviate(response.body()));
        throw new McpToolException("协调器返回 HTTP " + status + "：" + abbreviate(response.body()));
    }

    /** 协调器明确拒绝（400）—— 不重试。 */
    private static final class Rejected extends McpToolException {
        Rejected(String message) {
            super(message);
        }
    }

    /** 400 的响应体是 {@code {"message":"..."}}；解析失败时退回原始文本。 */
    private static String messageOf(String body) {
        try {
            JSONObject json = JSON.parseObject(body);
            if (json != null && json.getString("message") != null) {
                return json.getString("message");
            }
        } catch (Exception ignored) {
            // 落到原文
        }
        return abbreviate(body);
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 500 ? body : body.substring(0, 500) + "…";
    }
}
