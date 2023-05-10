package hawk.recall.controller;


import hawk.recall.service.IndexService;
import hawk.recall.service.SearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.net.UnknownHostException;

@RestController
@Slf4j
@RequestMapping("/cloud-control")
public class CloudControl {

    @Resource
    SearchService searchService;

    @Resource
    IndexService indexService;

    public CloudControl() throws UnknownHostException {
    }

    @RequestMapping(value = "/pull-index/{indexerHost}/{indexerPort}", method = RequestMethod.GET)
    @ResponseBody
    public String pullIndex(@PathVariable String indexerHost, @PathVariable int indexerPort){
        return indexService.pullIndex(indexerHost, indexerPort);
    }

    @RequestMapping(value = "/hot-switch-search-engine", method = RequestMethod.GET)
    @ResponseBody
    public String initSearchEngine(){
        searchService.hotSwitchSearchEngine();
        return "success";
    }

    @RequestMapping(value = "/init-demo-search-engine", method = RequestMethod.GET)
    @ResponseBody
    public String initDemoSearchEngine(){
        searchService.initDemoSearchEngine();
        return "success";
    }
}
