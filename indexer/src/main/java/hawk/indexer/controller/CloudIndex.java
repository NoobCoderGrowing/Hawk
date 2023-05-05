package hawk.indexer.controller;

import document.Document;
import hawk.indexer.service.IndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.ArrayList;

@RestController
@Slf4j
@RequestMapping("/cloud-index/")
public class CloudIndex{

    @Value("${shard.name}")
    String shardName;

    @Resource
    IndexService indexService;

    @RequestMapping("/index-document")
    public void indexDocument(Document doc){
        indexService.indexDocument(doc);
    }

    @RequestMapping("/index-documents")
    public void indexDocuments(ArrayList<Document> docs){
        indexService.indexDocuments(docs);
    }

    @RequestMapping("/commit")
    public void commit(){
        indexService.commit();
    }

    @RequestMapping("/clear-dir")
    public void clearDir(){
        indexService.clearDir();
    }

    @RequestMapping("/get-shardname")
    @ResponseBody
    public String getShardName(){
        return shardName;
    }
}
