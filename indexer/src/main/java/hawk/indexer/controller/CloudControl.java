package hawk.indexer.controller;

import document.Document;
import hawk.indexer.service.IndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

@RestController
@Slf4j
@RequestMapping("/cloud-control/")
public class CloudControl {

    private String ip = InetAddress.getLocalHost().getHostAddress();
    @Value("server.port")
    private String port;

    @Resource
    IndexService indexService;

    public CloudControl() throws UnknownHostException {
    }

    @RequestMapping("/index-document")
    public void indexDocument(@RequestBody Document doc){
        indexService.indexDocument(doc);
    }

    @RequestMapping(value = "/index-documents", method = RequestMethod.POST)
    @ResponseBody
    public String indexDocuments(@RequestBody List<Document> docs){
        long start = System.currentTimeMillis();
        indexService.indexDocuments(docs);
        long end = System.currentTimeMillis();
        String logInfo = "host: " + ip + ", port: " + port +", batch indexing takes " + (end - start) + " milliseconds";
        log.info(logInfo);
        return logInfo;
    }

    @RequestMapping("/commit")
    public void commit(){
        indexService.commit();
    }

    @RequestMapping("/refresh-index-writer")
    public void refreshIndexWriter(){
        indexService.refreshIndexWriter();
    }

    @RequestMapping("/get-index-path")
    public String getIndexPath(){
        return indexService.getIndexPath();
    }

    @RequestMapping("/clear-dir")
    public void clearDir(){
        indexService.clearDir();
    }

    @RequestMapping("/upload-index/{recallHost}/{recallPort}")
    @ResponseBody
    public String uploadIndex(@PathVariable String recallHost, @PathVariable int recallPort){
        String result = indexService.uploadIndex(recallHost, recallPort);
        return result;
    }

}
