package hawk.cloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Map;

/**
 * 分布式层的**唯一启动类** —— 一个 jar 起四种角色，由 {@code hawk.cloud.role} 决定装配哪一套。
 *
 * <h2>为什么必须显式排除各角色包</h2>
 * 本类落在 {@code hawk.cloud} 下，而 {@code @SpringBootApplication} 会**递归**扫描所在包 ——
 * 也就是会把 {@code hawk.cloud.indexer} / {@code .recall} / {@code .coordinator} / {@code .mcp}
 * 全扫进来。前三个包各自是一套**完整**的 Spring 组件：
 * <ul>
 *   <li>{@code POST /search} 在 recall 与 coordinator 里各有一个（语义完全不同）；</li>
 *   <li>{@code GET /health} 在 indexer 与 recall 里各有一个；</li>
 *   <li>{@code RegistryRegistrar} / {@code FastJsonWebConfig} / {@code RequestBodyErrorAdvice}
 *       三个角色各有一份。</li>
 * </ul>
 * 一起进容器的话，同名映射直接**启动失败**、同名 bean 按类型注入**歧义**。
 * {@code mcp} 包不会和它们撞映射，但同样不能被自动扫到 —— 否则每个角色都会装配 MCP 的
 * 工具 bean 与转发客户端。
 *
 * <p>所以下面用 {@code excludeFilters} 把各角色包排除在自动扫描之外，再由
 * {@link IndexerRole} / {@link RecallRole} / {@link CoordinatorRole} / {@link McpRole}
 * 按 role **有且只有一个**地显式 {@code @ComponentScan} 进来
 * （它们各自的扫描是独立的，不受这里的排除影响）。
 *
 * <p>⚠️ 加新角色包时，除了加一个 {@code *Role} 配置类，**还要把包名加进下面的正则** ——
 * 否则它会被自动扫进来。
 *
 * <p>{@code @EnableScheduling} 四个角色都用：索引器的重登记、召回端的拉取、协调器的节点轮询与
 * round tracker、mcp 的协调器地址发现，全是 {@code @Scheduled}。
 */
@SpringBootApplication
@ComponentScan(
        basePackages = "hawk.cloud",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "hawk\\.cloud\\.(indexer|recall|coordinator|mcp)\\..*"))
@EnableScheduling
public class CloudApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(CloudApplication.class);

        // 只加载本模块的 hawk-cloud.yml，不加载 classpath 上的 application.*。
        // 沿用原三个启动类的做法：classpath 上其他 jar 自带 application.properties 时会静默
        // 覆盖本模块的端口。改配置名从根上避开这类串扰。
        app.setDefaultProperties(Map.of("spring.config.name", "hawk-cloud"));
        app.run(args);
    }
}
