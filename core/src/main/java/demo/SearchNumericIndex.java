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

public class SearchNumericIndex {

    public static void main(String[] args) throws IOException {
        Directory directory = MMapDirectory.open(Paths.get("/opt/temp"));
        DirectoryReader directoryReader = DirectoryReader.open(directory);
        Analyzer analyzer = new NShortestPathAnalyzer(1);
        IndexConfig indexConfig = new IndexConfig(analyzer);
        Searcher searcher = new Searcher(directoryReader, indexConfig);
        NumericRangeQuery query = new NumericRangeQuery("price", 11.6, 12.56);
        ScoreDoc[] hits = searcher.search(query);
        hits = searcher.topN(hits, 10);
        for (int i = 0; i < hits.length; i++) {
            Document doc = searcher.doc(hits[i]);
            System.out.println(JSON.toJSONString(doc));
        }
        searcher.close();
    }
}
