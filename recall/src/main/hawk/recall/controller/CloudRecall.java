package hawk.recall.controller;

import document.Document;
import hawk.recall.service.SearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

@RestController
@Slf4j
@RequestMapping("/cloud-search/")
public class CloudRecall {

//    @Value("${shard.name}")
//    String shardName;

    @Resource
    SearchService searchService;

    @RequestMapping(value = "/search/{shard}/{query}", method = RequestMethod.GET)
    @ResponseBody
    public Document searchShard(@PathVariable String shard,@PathVariable String query){
        return null;
    }

    @RequestMapping(value = "/search/{query}", method = RequestMethod.GET)
    @ResponseBody
    public List<Document> search(@PathVariable String query){
        return searchService.search(query);
    }

}
