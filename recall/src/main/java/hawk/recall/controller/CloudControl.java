package hawk.recall.controller;


import hawk.recall.config.HostInfo;
import hawk.recall.service.IndexService;
import hawk.recall.service.SearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.Resource;
import java.net.UnknownHostException;

@RestController
@Slf4j
@RequestMapping("/cloud-control")
public class CloudControl {

    @Resource
    HostInfo hostInfo;

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

    @PostMapping(value = "/upload-index",
            consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
    @ResponseBody
    public String uploadIndex(@RequestPart("file") MultipartFile[] multipartFiles){
        Boolean result = indexService.storeIndex(multipartFiles);
        if(result){
            return "upload to recall host: " + hostInfo.getIp() + "port: " + hostInfo.getPort()  + " succeeded";
        }
        return "upload to recall host: " + hostInfo.getIp() + "port: " + hostInfo.getPort()  + " failed";
    }

}
