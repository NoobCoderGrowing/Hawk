package hawk.recall.controller;

import document.Document;
import hawk.recall.service.SearchService;
import lombok.extern.slf4j.Slf4j;
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

    @RequestMapping(value = "/search/range/{left}/{right}", method = RequestMethod.GET)
    @ResponseBody
    public List<Document> rangeSearch(@PathVariable Double left, @PathVariable Double right) {
        return searchService.rangeSearch(left, right);
    }

}
