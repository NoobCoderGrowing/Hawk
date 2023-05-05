package demo;

import com.alibaba.fastjson.JSON;
import directory.Directory;
import directory.MMapDirectory;
import document.Document;
import hawk.segment.core.anlyzer.Analyzer;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;
import reader.DirectoryReader;
import search.*;
import writer.IndexConfig;

import java.nio.file.Paths;

public class SearchByStringQuery {

    public static void main(String[] args) {
        Directory directory = MMapDirectory.open(Paths.get("/opt/temp/shard1"));
        DirectoryReader directoryReader = DirectoryReader.open(directory);
        Analyzer analyzer = new NShortestPathAnalyzer(1);
        IndexConfig indexConfig = new IndexConfig(analyzer);
        Searcher searcher = new Searcher(directoryReader, indexConfig);
        Query query = new StringQuery("title", "");
        ScoreDoc[] hits = searcher.search(query, 10000);
        for (int i = 0; i < hits.length; i++) {
            Document doc = searcher.doc(hits[i]);
            System.out.println("hit " + i + "====> " +JSON.toJSONString(doc));
        }
        searcher.close();
    }
}
