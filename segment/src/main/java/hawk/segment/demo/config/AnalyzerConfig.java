package hawk.segment.demo.config;

import hawk.segment.demo.DemoAnalyzer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnalyzerConfig {


    @Bean
    public DemoAnalyzer getDemoAnalyzer(){
        return new DemoAnalyzer();
    }
}
