package hawk.index.demo;

import hawk.index.core.directory.Directory;
import hawk.index.core.directory.MMapDirectory;
import hawk.index.core.document.Document;
import hawk.index.core.reader.DirectoryReader;
import hawk.index.core.search.NumericRangeQuery;
import hawk.index.core.search.Query;
import hawk.index.core.search.Searcher;
import hawk.index.core.search.TermQuery;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

public class SearchIndex {

    public static void main(String[] args) throws IOException {
        Directory directory = MMapDirectory.open(Paths.get("/opt/temp"));
        DirectoryReader directoryReader =DirectoryReader.open(directory);
        Searcher searcher = new Searcher(directoryReader);
        Query query = new TermQuery("title", "剃须刀");
        Query query2 = new NumericRangeQuery("price", 1, 30);
//        int[][] postingList = searcher.search(query);
    }
}
