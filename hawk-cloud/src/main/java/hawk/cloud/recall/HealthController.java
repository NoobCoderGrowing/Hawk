package hawk.cloud.recall;

import org.springframework.beans.factory.annotation.Value;
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
    private final LocalIndexStore local;

    /**
     * 索引根目录 —— 只为了报它所在分区的剩余空间。
     *
     * <p>索引器推送前会 GET 这个端点看 {@code freeBytes}：**不够就一个字节都不传**、
     * 直接记失败（方案 §12.9a）。这样磁盘满不用等传完 200 MB 才发现。
     * 查不到这个端点的索引器会**照推**（fail-open）—— 所以这里挂了只影响"提前发现"，
     * 不影响数据通路。
     */
    @Value("${hawk.cloud.recall.index-dir:cloud/recall}")
    private String indexDir;

    public HealthController(RegistryRegistrar registrar, LocalIndexStore local) {
        this.registrar = registrar;
        this.local = local;
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
        body.put("freeBytes", freeBytes());
        // 本节点**当前服务的那一份**索引是第几轮建的。协调器的 round tracker 判据读的就是它：
        // 「至少有一个召回副本报的 round == 当前轮次」= 这一轮建成了。
        // null = 还没有任何版本，或者当前版本是旁路调用（没经协调器）建的、没有 round 文件。
        body.put("round", local.store().newestRound());
        return body;
    }

    /** 索引目录所在分区的可用字节数；查不到返回 null（索引器那边会当成"照推"）。 */
    private Long freeBytes() {
        try {
            // 目录可能还不存在（从没收到过推送），退到最近的已存在祖先
            java.nio.file.Path p = java.nio.file.Path.of(indexDir).toAbsolutePath();
            while (p != null && !java.nio.file.Files.exists(p)) {
                p = p.getParent();
            }
            return p == null ? null : java.nio.file.Files.getFileStore(p).getUsableSpace();
        } catch (Exception e) {
            return null;
        }
    }
}
