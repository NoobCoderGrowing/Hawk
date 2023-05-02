package hawk.index.distribute.service;


import hawk.index.core.document.Document;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.ArrayList;

@FeignClient("indexer")
@RequestMapping("/cloud-index/")
public interface IndexClient {

    @RequestMapping("/index-document")
    public void indexDocument(Document doc);

    @RequestMapping("/index-documents")
    public void indexDocuments(ArrayList<Document> docs);

    @RequestMapping("/commit")
    public void commit();

    @RequestMapping("/clearDir")
    public void clearDir();

}
