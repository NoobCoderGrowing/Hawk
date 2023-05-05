package hawk.recall.service;

import com.alibaba.fastjson.JSON;
import directory.Directory;
import directory.MMapDirectory;
import document.Document;
import hawk.segment.core.anlyzer.Analyzer;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reader.DirectoryReader;
import search.Query;
import search.ScoreDoc;
import search.Searcher;
import search.StringQuery;
import writer.IndexConfig;


import javax.annotation.PostConstruct;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class SearchService {

    private Searcher searchEngine;

    private ReentrantReadWriteLock readWriteLock;

    @Value("${index.path}")
    private String indexPath;

    @PostConstruct
    public void initSearchEngine(){
        Directory directory = MMapDirectory.open(Paths.get(indexPath));
        DirectoryReader directoryReader = DirectoryReader.open(directory);
        Analyzer analyzer = new NShortestPathAnalyzer(1);
        IndexConfig indexConfig = new IndexConfig(analyzer);
        Searcher searcher = new Searcher(directoryReader, indexConfig);
        this.searchEngine = searcher;
        this.readWriteLock = new ReentrantReadWriteLock();
    }

    public List<Document> search(String query){
        List<Document> result = new ArrayList<>();
        Query strQuery = new StringQuery("title", query);
        readWriteLock.readLock().lock(); // allow concurrent read, but disallow reading while switching engine
        ScoreDoc[] hits = this.searchEngine.search(strQuery, 1000);
        for (int i = 0; i < hits.length; i++) {
            Document doc = this.searchEngine.doc(hits[i]);
            result.add(doc);
        }
        readWriteLock.readLock().unlock();
        return result;
    }



    public synchronized void switchSearchEngine(){
        // init a new search engine
        Directory directory = MMapDirectory.open(Paths.get(indexPath));
        DirectoryReader directoryReader = DirectoryReader.open(directory);
        Analyzer analyzer = new NShortestPathAnalyzer(1);
        IndexConfig indexConfig = new IndexConfig(analyzer);
        Searcher newSearcher = new Searcher(directoryReader, indexConfig);
        // replace the old with the new
        readWriteLock.writeLock().lock();
        Searcher oldSearcher = this.searchEngine;
        this.searchEngine = newSearcher;
        readWriteLock.writeLock().unlock();
        oldSearcher.close();
    }

    public String setIndexPath(String path){
        this.indexPath = path;
        return this.indexPath;
    }

    public Boolean pullIndex(String tempStamp){
        return null;
    }


}
