package hawk.shard.contoller;

import hawk.shard.service.CloudIndexing;
import hawk.shard.service.ShardMapWatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@Slf4j
@RequestMapping("/cloud-shard-control/")
public class CloudShardController {

    @Resource
    ShardMapWatcher shardMapWatcher;

    @Resource
    CloudIndexing cloudIndexing;


    @RequestMapping("/cloud-indexing")
    @ResponseBody
    @Scheduled(cron = "0 10 0 * * *")
    public String cloudIndexingAndPullAndSwitch(){
        ConcurrentHashMap<String, List<ServiceInstance>> indexerMap = shardMapWatcher.refreshIndexShardMap();
        long start = System.currentTimeMillis();
        cloudIndexing.refreshIndexWriter(indexerMap);
        cloudIndexing.cloudIndexingFromFileData(indexerMap);
        cloudIndexing.cloudCommit(indexerMap);
        long end = System.currentTimeMillis();
        log.info("cloud indexing takes " + (end - start) + " milliseconds");

        start = System.currentTimeMillis();
        ConcurrentHashMap<String, List<ServiceInstance>> recallMap = shardMapWatcher.refreshRecallShardMap();
        cloudIndexing.cloudPullIndex(recallMap, indexerMap);
        end = System.currentTimeMillis();
        log.info("cloud pull index takes " + (end - start) + " milliseconds");

        start = System.currentTimeMillis();
        cloudIndexing.cloudSwitchSearchEngine(recallMap);
        end = System.currentTimeMillis();
        log.info("cloud switch searcher " + (end - start) + " milliseconds");

        return "success";
    }
}
