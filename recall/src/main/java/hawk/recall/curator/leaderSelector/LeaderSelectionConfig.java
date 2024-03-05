package hawk.recall.curator.leaderSelector;


import java.io.IOException;

import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.annotation.PostConstruct;

@Configuration
@Slf4j
public class LeaderSelectionConfig {

    @Autowired
    CuratorFramework curatorFramework;

    @Autowired
    Environment env;

    @Value("${spring.cloud.nacos.discovery.group}")
    private String shardName;

    @Value("${spring.cloud.nacos.discovery.cluster-name}")
    private String clusterName;

    @Value("${spring.cloud.nacos.discovery.metadata.nodeId}")
    private String nodeId;

    @PostConstruct
    public void leaderSelection() {
       String path = "/" + shardName + "/" + clusterName;
        SelectorClient client = new SelectorClient(curatorFramework, path, "Client #" + nodeId);
        client.start();
    }
}
