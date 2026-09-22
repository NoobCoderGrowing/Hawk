package hawk.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Map;

@SpringBootApplication
public class WebApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(WebApplication.class);

        // 只加载本模块的 hawk-web.yml，不加载 classpath 上的 application.*。
        //
        // 原因：segment 模块（recall 的传递依赖）在它的 jar 里带了 application.properties，
        // 里面有 server.port=3333 —— 那是给 segment 自己的演示应用用的。
        // Spring Boot 会把两份都加载，而 .properties 的优先级高于 .yml，
        // 结果是本模块的 server.port 被静默覆盖，Tomcat 跑到 3333 上。
        //
        // 改配置名可以从根上避开这类跨模块串扰：依赖 jar 里不会有人叫 hawk-web.yml。
        app.setDefaultProperties(Map.of("spring.config.name", "hawk-web"));
        app.run(args);
    }
}
