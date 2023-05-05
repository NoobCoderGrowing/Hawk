package hawk.indexer.service;

import directory.MMapDirectory;
import document.Document;
import writer.IndexWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

@Service
public class IndexService {

    @Autowired
    MMapDirectory directory;

    @Autowired
    IndexWriter indexWriter;

    public void indexDocument(Document doc) {
        indexWriter.addDoc(doc);
    }

    public void indexDocuments(ArrayList<Document> docs) {
        for (int i = 0; i < docs.size(); i++) {
            indexWriter.addDoc(docs.get(i));
        }
    }

    public void commit() {
        indexWriter.commit(false);
    }

    public void clearDir(){
        directory.cleanDir();
    }
}
