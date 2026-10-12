package hawk.cloud.recall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Map;

@SpringBootApplication
@EnableScheduling   // RegistryRegistrar#reRegister 靠 @Scheduled 每分钟心跳式重登记
public class RecallApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(RecallApplication.class);

        // 只加载本模块的 hawk-cloud-recall.yml，不加载 classpath 上的 application.*。
        // 同 web：segment 的 jar 里带了 application.properties（server.port=3333），
        // 优先级高于 .yml，会静默覆盖本模块的端口。改配置名从根上避开这类串扰。
        app.setDefaultProperties(Map.of("spring.config.name", "hawk-cloud-recall"));
        app.run(args);
    }
}
