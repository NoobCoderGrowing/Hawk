package hawk.indexer.service;

import directory.MMapDirectory;
import document.Document;
import hawk.segment.core.anlyzer.Analyzer;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import util.DateUtil;
import writer.IndexConfig;
import writer.IndexWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
@Slf4j
public class IndexService {

    @Value("${base.path}")
    String basePath;

    @Autowired
    Environment env;

    private IndexWriter indexWriter;

    private ReentrantReadWriteLock writerLock = new ReentrantReadWriteLock();

    WebClient webClient = WebClient.create();

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

    public String uploadIndex(String recallHost, int recallPort){
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        String indexPath = getIndexPath();
        File indexDir = new File(indexPath);
        File[] files = indexDir.listFiles();
        for (int i = 0; i < files.length; i++) {
            builder.part("file", new FileSystemResource(files[i]));
        }
        String url = "http://".concat(recallHost).concat(":").concat(Integer.toString(recallPort)).
                concat("/cloud-control/upload-index");
        return webClient.post().uri(url).contentType(MediaType.MULTIPART_FORM_DATA).
                body(BodyInserters.fromMultipartData(builder.build())).retrieve().bodyToMono(String.class).
                block();
    }
}
