package hawk.index.distribute.config;


import directory.MMapDirectory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.nio.file.Paths;

@Configuration
@Order(1)
public class DirectoryConfig {

    @Bean
    public MMapDirectory initDirectory(){
        MMapDirectory mMapDirectory = new MMapDirectory(Paths.get("/opt/shard1"));
        return mMapDirectory;
    }
}
