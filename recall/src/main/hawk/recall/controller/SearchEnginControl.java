package hawk.recall.controller;


import hawk.recall.service.IndexService;
import hawk.recall.service.SearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@Slf4j
@RequestMapping("/cloud-control/")
public class SearchEnginControl {
    @Resource
    SearchService searchService;

    @Resource
    IndexService indexService;

    @RequestMapping(value = "/init-search-engine", method = RequestMethod.GET)
    @ResponseBody
    public String initSearchEngine(){
        searchService.initSearchEngine();
        return "success";
    }

    @RequestMapping(value = "/init-demo-search-engine", method = RequestMethod.GET)
    @ResponseBody
    public String initDemoSearchEngine(){
        searchService.initDemoSearchEngine();
        return "success";
    }


    @RequestMapping(value = "/load-index", method = RequestMethod.GET)
    @ResponseBody
    public boolean loadIndex(){
        return indexService.loadIndex();
    }
}
