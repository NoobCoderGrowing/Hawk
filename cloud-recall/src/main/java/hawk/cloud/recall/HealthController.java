package hawk.cloud.recall;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查接口：{@code GET /health}。
 *
 * <pre>
 * {
 *   "status": "UP",
 *   "role": "recall",
 *   "shardNum": "shard-1",
 *   "shardId": "id-0",
 *   "key": "/cloud/recall/shard-1/id-0",
 *   "address": "127.0.0.1:8092",
 *   "registry": { "configured": true, "registered": true }
 * }
 * </pre>
 *
 * <h2>恒为 200</h2>
 * 「连不上注册中心」不改变状态码，只体现在 {@code registry.registered} 里。理由：本地开发
 * 常常没有注册中心，把它判成不健康会让这个接口在日常开发里恒为失败、失去意义。
 * 需要据此摘流量的上游，请自行读 {@code registry.registered}。
 *
 * <p>没有走 Spring Boot Actuator：本项目其余模块都没引它，为一个端点拉进一整个 actuator
 * 依赖不划算。
 */
@RestController
public class HealthController {

    private final RegistryRegistrar registrar;

    public HealthController(RegistryRegistrar registrar) {
        this.registrar = registrar;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> registry = new LinkedHashMap<>();
        registry.put("configured", registrar.isRegistryConfigured());
        registry.put("registered", registrar.isRegistered());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("role", registrar.role());
        body.put("shardNum", registrar.shardNum());
        body.put("shardId", registrar.shardId());
        body.put("key", registrar.nodeKey());
        body.put("address", registrar.address());
        body.put("registry", registry);
        return body;
    }
}
