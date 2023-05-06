package hawk.shard.service;


import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.RequestMapping;

@FeignClient("indexer")
@RequestMapping
public interface IndexClient {

    @RequestMapping("/cloud-index/commit")
    public void commit();

    @RequestMapping("/cloud-index/clear-dir")
    public void clearDir();
}
