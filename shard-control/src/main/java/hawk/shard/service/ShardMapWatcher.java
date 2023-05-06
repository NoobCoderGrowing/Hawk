package hawk.shard.service;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
@EnableScheduling
public class ShardMapWatcher {

    @Resource
    private DiscoveryClient discoveryClient;

    ConcurrentHashMap<String, ServiceInstance> indexerShardLeaderMap = new ConcurrentHashMap<>();
    ConcurrentHashMap<String, List<ServiceInstance>> indexerShardFollowerMap = new ConcurrentHashMap<>();

    ConcurrentHashMap<String, List<ServiceInstance>> recallShardMap = new ConcurrentHashMap<>();

    public void refreshIndexShardMap(){
        List<ServiceInstance> instances = discoveryClient.getInstances("indexer");
        if(instances == null || instances.size() == 0) {// if nacos down, use previous map
            return;
        }
        indexerShardLeaderMap.clear();
        indexerShardFollowerMap.clear();
        for (int i = 0; i < instances.size(); i++) {
            String host = instances.get(i).getHost();
            int port = instances.get(i).getPort();
            RestTemplate restTemplate = new RestTemplate();
            String url = "http://".concat(host).concat(":").concat(Integer.toString(port)).
                    concat("/cloud-index/get-shardname");
            String shardName = restTemplate.getForObject(url, String.class);
            if(!shardName.contains("follower")){
                indexerShardLeaderMap.put(shardName, instances.get(i));
            }else{
                List<ServiceInstance> followerList = indexerShardFollowerMap.get(shardName);
                if (followerList != null) {
                    followerList.add(instances.get(i));
                } else {
                    followerList = new ArrayList<>();
                    followerList.add(instances.get(i));
                    indexerShardFollowerMap.put(shardName, followerList);
                }
            }
        }
        //could add some warning and leader selection logic
    }

    public void refreshRecallShardMap(){
        List<ServiceInstance> instances = discoveryClient.getInstances("recall");
        if(instances == null || instances.size() == 0) {// if nacos down, use previous map
            return;
        }
        recallShardMap.clear();
        for (int i = 0; i < instances.size(); i++) {
            String host = instances.get(i).getHost();
            int port = instances.get(i).getPort();
            RestTemplate restTemplate = new RestTemplate();
            String url = "http://".concat(host).concat(":").concat(Integer.toString(port)).
                    concat("/cloud-index/get-shardname");
            String shardName = restTemplate.getForObject(url, String.class);
            List<ServiceInstance> shardList = recallShardMap.get(shardName);
            if (shardList != null) {
                shardList.add(instances.get(i));
            } else {
                shardList = new ArrayList<>();
                shardList.add(instances.get(i));
                recallShardMap.put(shardName, shardList);
            }
        }
        //could add some warning logic for the number of
    }

}
