package hawk.indexer.service;

import directory.MMapDirectory;
import document.Document;
import hawk.segment.core.anlyzer.Analyzer;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import util.DateUtil;
import writer.IndexConfig;
import writer.IndexWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class IndexService {

    @Value("${base.path}")
    String basePath;

    @Autowired
    Environment env;

    private IndexWriter indexWriter;

    private ReentrantReadWriteLock writerLock = new ReentrantReadWriteLock();

//    @PostConstruct
    public void refreshIndexWriter(){
        writerLock.writeLock().lock();
        String indexPath = getIndexPath();
        MMapDirectory mMapDirectory = MMapDirectory.open(Paths.get(indexPath));
        Analyzer analyzer = new NShortestPathAnalyzer(1);
        IndexConfig indexConfig = new IndexConfig(analyzer);
        this.indexWriter = new IndexWriter(indexConfig, mMapDirectory);
        writerLock.writeLock().unlock();
    }

    public String getIndexPath(){
        String applicationName = env.getProperty("spring.application.name");
        String dateStr = DateUtil.getDateStr();
        String shard = env.getProperty("spring.cloud.nacos.discovery.metadata.shard");
        String indexPath = basePath.concat("/").concat(applicationName).concat("/").concat(dateStr).concat("/").concat(shard);
        return indexPath;
    }

    public void indexDocument(Document doc) {
        writerLock.readLock().lock();
        indexWriter.addDoc(doc);
        writerLock.readLock().unlock();
    }

    public void indexDocuments(List<Document> docs) {
        writerLock.readLock().lock();
        for (int i = 0; i < docs.size(); i++) {
            indexWriter.addDoc(docs.get(i));
        }
        writerLock.readLock().unlock();
    }

    public void commit() {
        writerLock.readLock().lock();
        indexWriter.commit();
        writerLock.readLock().unlock();
    }

    public void clearDir(){
    }
}
