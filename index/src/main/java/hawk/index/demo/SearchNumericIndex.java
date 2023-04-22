package hawk.index.demo;

import com.alibaba.fastjson.JSON;
import hawk.index.core.directory.Directory;
import hawk.index.core.directory.MMapDirectory;
import hawk.index.core.document.Document;
import hawk.index.core.reader.DirectoryReader;
import hawk.index.core.search.*;
import hawk.index.core.writer.IndexConfig;
import hawk.segment.core.anlyzer.Analyzer;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;

import java.io.IOException;
import java.nio.file.Paths;

public class SearchNumericIndex {

    public static void main(String[] args) throws IOException {
        Directory directory = MMapDirectory.open(Paths.get("/opt/temp"));
        DirectoryReader directoryReader =DirectoryReader.open(directory);
        Analyzer analyzer = new NShortestPathAnalyzer(1);
        IndexConfig indexConfig = new IndexConfig(analyzer);
        Searcher searcher = new Searcher(directoryReader, indexConfig);
        NumericRangeQuery query = new NumericRangeQuery("price", 11.0, 12.5);
        ScoreDoc[] hits = searcher.search(query);
        hits = searcher.topN(hits, 10);
        for (int i = 0; i < hits.length; i++) {
            Document doc = searcher.doc(hits[i]);
            System.out.println(JSON.toJSONString(doc));
        }
        searcher.close();
    }
}
