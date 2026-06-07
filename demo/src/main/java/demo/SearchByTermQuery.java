package demo;

import com.alibaba.fastjson.JSON;
import directory.Directory;
import directory.MMapDirectory;
import document.Document;
import hawk.indexer.writer.config.IndexConfig;
import hawk.recall.config.SearchConfig;
import hawk.recall.query.Query;
import hawk.recall.query.TermQuery;
import hawk.recall.reader.DirectoryReader;
import hawk.recall.search.*;
import hawk.segment.core.anlyzer.Analyzer;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;

import java.io.IOException;
import java.nio.file.Paths;

public class SearchByTermQuery {

    public static void main(String[] args) throws IOException {
        Directory directory = MMapDirectory.open(Paths.get("/opt/temp/2022"));
        DirectoryReader directoryReader = DirectoryReader.open(directory);
        Analyzer analyzer = new NShortestPathAnalyzer(1);
        IndexConfig indexConfig = new IndexConfig(analyzer);
        Searcher searcher = new Searcher(directoryReader, new SearchConfig(analyzer), indexConfig);
        Query query = new TermQuery("title", "剃须刀");
        ScoreDoc[] hits = searcher.search(query, 10);
        Document doc = searcher.doc(hits[0]);
        System.out.println(JSON.toJSONString(doc));
        searcher.close();
    }
}
