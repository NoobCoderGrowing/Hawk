//package hawk.recall.curator.latch;
//
//import lombok.extern.slf4j.Slf4j;
//import org.apache.curator.framework.CuratorFramework;
//import org.apache.curator.framework.recipes.leader.Participant;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.core.env.Environment;
//
//import javax.annotation.PostConstruct;
//
//@Configuration
//@Slf4j
//public class LatchConfig {
//
//    @Autowired
//    CuratorFramework curatorFramework;
//    @Autowired
//    Environment env;
//
//    @Value("${spring.cloud.nacos.discovery.group}")
//    private String shardName;
//
//    @Value("${spring.cloud.nacos.discovery.cluster-name}")
//    private String clusterName;
//
//    @Value("${spring.cloud.nacos.discovery.metadata.nodeId}")
//    private String nodeId;
//
//    private LatchClient latchClient;
//
//    public LatchConfig() {
//    }
//
//    @PostConstruct
//    public void leaderSelection(){
//        String path = "/" + shardName + "/" + clusterName;
//        latchClient = new LatchClient(curatorFramework, path, nodeId);
//        try {
//            latchClient.start();
//        } catch (Exception e) {
//            log.error(e.getMessage());
//            e.printStackTrace();
//        }
//    }
//}
