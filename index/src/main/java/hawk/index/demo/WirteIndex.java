package hawk.index.demo;

import hawk.index.core.directory.MMapDirectory;
import hawk.index.core.document.Document;
import hawk.index.core.field.DoubleField;
import hawk.index.core.field.Field;
import hawk.index.core.field.StringField;
import hawk.index.core.search.Query;
import hawk.index.core.search.TermQuery;
import hawk.index.core.writer.IndexWriter;
import hawk.index.core.writer.IndexConfig;
import hawk.segment.core.anlyzer.Analyzer;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;

import java.io.IOException;
import java.nio.file.Paths;

public class WirteIndex {

    public static void main(String[] args) throws IOException {
        MMapDirectory mMapDirectory = new MMapDirectory(Paths.get("/opt/temp"));
        Analyzer analyzer = new NShortestPathAnalyzer(1);
        IndexConfig indexConfig = new IndexConfig(analyzer);
        IndexWriter indexWriter = new IndexWriter(indexConfig, mMapDirectory);
        Document doc = new Document();
        StringField field = new StringField("title", "剃须刀", Field.Tokenized.YES, Field.Stored.YES);
        doc.add(field);
        indexWriter.addDoc(doc);
        indexWriter.commit();
    }
}
