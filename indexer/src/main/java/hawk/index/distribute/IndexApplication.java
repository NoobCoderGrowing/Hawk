package hawk.index.distribute;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class IndexApplication {

    public static void main(String[] args) {
        SpringApplication.run(IndexApplication.class, args
        );
    }
}
