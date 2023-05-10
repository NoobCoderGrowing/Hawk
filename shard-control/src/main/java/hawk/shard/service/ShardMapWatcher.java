package hawk.shard.service;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
@EnableScheduling
public class ShardMapWatcher {

    @Resource
    private DiscoveryClient discoveryClient;

    ConcurrentHashMap<String, List<ServiceInstance>> indexerShardMap = new ConcurrentHashMap<>();

    ConcurrentHashMap<String, List<ServiceInstance>> recallShardMap = new ConcurrentHashMap<>();

    public ConcurrentHashMap<String, List<ServiceInstance>> refreshIndexShardMap(){
        ConcurrentHashMap<String, List<ServiceInstance>> newShardMap = new ConcurrentHashMap<>();
        List<ServiceInstance> instances = discoveryClient.getInstances("indexer");
        if(instances == null || instances.size() == 0) {// if nacos down, use previous map
            return indexerShardMap;
        }
        for (int i = 0; i < instances.size(); i++) {
            String shardName = instances.get(i).getMetadata().get("shard");
            List<ServiceInstance> shardList = newShardMap.get(shardName);
            if (shardList != null) {
                shardList.add(instances.get(i));
            } else {
                shardList = new ArrayList<>();
                shardList.add(instances.get(i));
                newShardMap.put(shardName, shardList);
            }
        }
        indexerShardMap = newShardMap;
        return indexerShardMap;
        //could add some warning logic for the number of
    }

    public ConcurrentHashMap<String, List<ServiceInstance>> refreshRecallShardMap(){
        ConcurrentHashMap<String, List<ServiceInstance>> newShardMap = new ConcurrentHashMap<>();
        List<ServiceInstance> instances = discoveryClient.getInstances("recall");
        if(instances == null || instances.size() == 0) {// if nacos down, use previous map
            return recallShardMap;
        }
        for (int i = 0; i < instances.size(); i++) {
            String shardName = instances.get(i).getMetadata().get("shard");
            List<ServiceInstance> shardList = newShardMap.get(shardName);
            if (shardList != null) {
                shardList.add(instances.get(i));
            } else {
                shardList = new ArrayList<>();
                shardList.add(instances.get(i));
                newShardMap.put(shardName, shardList);
            }
        }
        recallShardMap = newShardMap;
        return recallShardMap;
        //could add some warning logic for the number of
    }

}
