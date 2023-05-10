package demo;

import com.alibaba.fastjson.JSON;
import directory.Directory;
import directory.MMapDirectory;
import document.Document;
import field.DoubleField;
import hawk.segment.core.anlyzer.Analyzer;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;
import reader.DirectoryReader;
import search.NumericRangeQuery;
import search.ScoreDoc;
import search.Searcher;
import writer.IndexConfig;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SearchByNumericRangeQuery {

    public static void main(String[] args) throws IOException {
        Directory directory = MMapDirectory.open(Paths.get("/opt/temp/shard1"));
        DirectoryReader directoryReader = DirectoryReader.open(directory);
        Analyzer analyzer = new NShortestPathAnalyzer(1);
        IndexConfig indexConfig = new IndexConfig(analyzer);
        Searcher searcher = new Searcher(directoryReader, indexConfig);
        NumericRangeQuery query = new NumericRangeQuery("price", 5.7, 18.56);
        ScoreDoc[] hits = searcher.search(query, 10);
        List<Document> documents = new ArrayList<>();
        for (int i = 0; i < hits.length; i++) {
            documents.add(searcher.doc(hits[i]));
        }
        Collections.sort(documents,(a,b)->{
            double priceA = ((DoubleField) a.getFieldMap().get("price")).getValue();
            double priceB = ((DoubleField)b.getFieldMap().get("price")).getValue();
            return Double.compare(priceA,priceB);
        });

        for (int i = 0; i < 500; i++) {
            System.out.println("hit " + i + "====> " +JSON.toJSONString(documents.get(i)));
        }

        searcher.close();
    }
}
