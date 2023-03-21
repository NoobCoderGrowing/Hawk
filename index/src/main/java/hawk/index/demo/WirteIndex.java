package hawk.index.demo;

import hawk.index.core.directory.MMapDirectory;
import hawk.index.core.document.Document;
import hawk.index.core.field.Field;
import hawk.index.core.field.StringField;
import hawk.index.core.writer.IndexWriter;
import hawk.index.core.writer.IndexWriterConfig;
import hawk.segment.core.anlyzer.Analyzer;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;

import java.io.IOException;
import java.nio.file.Paths;

public class WirteIndex {

    public static void main(String[] args) throws IOException {
        MMapDirectory mMapDirectory = new MMapDirectory(Paths.get("/opt/temp"));
        Analyzer analyzer = new NShortestPathAnalyzer(1);
        IndexWriterConfig indexWriterConfig = new IndexWriterConfig(analyzer,2);
        IndexWriter indexWriter = new IndexWriter(indexWriterConfig, mMapDirectory);
        Document doc = new Document();
        StringField field = new StringField("title", "可爱", Field.Tokenized.YES, Field.Stored.YES);
        doc.add(field);
        indexWriter.addDoc(doc);
        indexWriter.commit();
    }
}
