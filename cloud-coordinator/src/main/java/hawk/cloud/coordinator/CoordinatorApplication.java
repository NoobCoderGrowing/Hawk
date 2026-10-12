package hawk.cloud.coordinator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Map;

@SpringBootApplication
public class CoordinatorApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(CoordinatorApplication.class);

        // 只加载本模块的 hawk-cloud-coordinator.yml，不加载 classpath 上的 application.*。
        // 同 web 模块：segment 的 jar 里带了 application.properties（server.port=3333），
        // Spring Boot 会把两份都加载，且 .properties 优先级更高，会静默覆盖本模块的端口。
        // 改配置名可以从根上避开这类跨模块串扰。
        app.setDefaultProperties(Map.of("spring.config.name", "hawk-cloud-coordinator"));
        app.run(args);
    }
}
