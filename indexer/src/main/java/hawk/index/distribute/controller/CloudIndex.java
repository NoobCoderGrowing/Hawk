package hawk.index.distribute.controller;

import document.Document;
import hawk.index.distribute.service.IndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.ArrayList;

@RestController
@Slf4j
@RequestMapping("/cloud-index/")
public class CloudIndex{

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

    @RequestMapping("/clearDir")
    public void clearDir(){
        indexService.clearDir();
    }

}
