package demo;

import com.alibaba.fastjson.JSON;
import directory.Directory;
import directory.MMapDirectory;
import document.Document;
import field.PrimaryKeyField;
import hawk.indexer.writer.config.IndexConfig;
import hawk.recall.admin.IndexAdmin;
import hawk.recall.config.SearchConfig;
import hawk.recall.query.StringQuery;
import hawk.recall.reader.DirectoryReader;
import hawk.recall.search.ScoreDoc;
import hawk.recall.search.Searcher;
import hawk.segment.core.anlyzer.Analyzer;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;

import java.nio.file.Paths;

public class DeleteDocDemo {

    public static void main(String[] args) {
        Directory directory = MMapDirectory.open(Paths.get("/opt/index/1"));
        DirectoryReader directoryReader = DirectoryReader.open(directory);
        Analyzer analyzer = new NShortestPathAnalyzer(1);
        IndexConfig indexConfig = new IndexConfig(analyzer);
        Searcher searcher = new Searcher(directoryReader, new SearchConfig(analyzer), indexConfig);
        IndexAdmin indexAdmin = new IndexAdmin(directoryReader, directory);

        StringQuery query = new StringQuery("title", "丰田");
        ScoreDoc[] hitsBefore = searcher.search(query, 10000);
        System.out.println("hits before delete: " + hitsBefore.length);

        long uniqueIDToDelete = -1;
        for (ScoreDoc hit : hitsBefore) {
            Document doc = searcher.doc(hit);
            if (doc == null) {
                continue;
            }
            PrimaryKeyField pk = (PrimaryKeyField) doc.getFieldMap().get("uniqueID");
            if (pk != null) {
                uniqueIDToDelete = pk.getValue();
                System.out.println("will delete uniqueID=" + uniqueIDToDelete + ", doc=" + JSON.toJSONString(doc));
                break;
            }
        }

        if (uniqueIDToDelete < 0) {
            System.out.println("no document with PrimaryKeyField found in search results");
            searcher.close();
            return;
        }

        boolean deleted = indexAdmin.deleteDoc(uniqueIDToDelete);
        System.out.println("deleteDoc returned: " + deleted);
        boolean deletedAgain = indexAdmin.deleteDoc(uniqueIDToDelete);
        System.out.println("deleteDoc again returned: " + deletedAgain);

        ScoreDoc[] hitsAfter = searcher.search(query, 10000);
        System.out.println("hits after delete: " + hitsAfter.length);

        for (ScoreDoc hit : hitsAfter) {
            Document doc = searcher.doc(hit);
            if (doc == null) {
                continue;
            }
            PrimaryKeyField pk = (PrimaryKeyField) doc.getFieldMap().get("uniqueID");
            if (pk != null && pk.getValue() == uniqueIDToDelete) {
                System.out.println("ERROR: deleted uniqueID still appears in results");
                searcher.close();
                return;
            }
        }
        System.out.println("deleted uniqueID no longer appears in search results");
        searcher.close();
    }
}
