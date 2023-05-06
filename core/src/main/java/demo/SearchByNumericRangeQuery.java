package demo;

import com.alibaba.fastjson.JSON;
import directory.Directory;
import directory.MMapDirectory;
import document.Document;
import hawk.segment.core.anlyzer.Analyzer;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;
import reader.DirectoryReader;
import search.NumericRangeQuery;
import search.ScoreDoc;
import search.Searcher;
import writer.IndexConfig;

import java.io.IOException;
import java.nio.file.Paths;

public class SearchByNumericRangeQuery {

    public static void main(String[] args) throws IOException {
        Directory directory = MMapDirectory.open(Paths.get("/opt/temp/shard1"));
        DirectoryReader directoryReader = DirectoryReader.open(directory);
        Analyzer analyzer = new NShortestPathAnalyzer(1);
        IndexConfig indexConfig = new IndexConfig(analyzer);
        Searcher searcher = new Searcher(directoryReader, indexConfig);
        NumericRangeQuery query = new NumericRangeQuery("price", 5.50, 18.56);
        ScoreDoc[] hits = searcher.search(query, 100);
        for (int i = 0; i < hits.length; i++) {
            Document doc = searcher.doc(hits[i]);
            System.out.println(JSON.toJSONString(doc));
        }
        searcher.close();
    }
}
