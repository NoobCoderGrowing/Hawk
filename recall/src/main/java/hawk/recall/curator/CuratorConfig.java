package hawk.recall.curator;

import lombok.Data;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class CuratorConfig {

    public CuratorConfig() {
    }

    @Autowired
    Environment env;

    @Bean
    public CuratorFramework curatorFramework(){
        String connectionString = env.getProperty("zookeeper.address");
        CuratorFramework client = CuratorFrameworkFactory.newClient(connectionString, new ExponentialBackoffRetry(1000,3));
        client.start();
        return client;
    }
}
