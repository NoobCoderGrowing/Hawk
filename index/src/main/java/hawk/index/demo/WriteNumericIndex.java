package hawk.index.demo;

import hawk.index.core.directory.MMapDirectory;
import hawk.index.core.document.Document;
import hawk.index.core.field.DoubleField;
import hawk.index.core.field.Field;
import hawk.index.core.writer.IndexConfig;
import hawk.index.core.writer.IndexWriter;
import hawk.segment.core.anlyzer.Analyzer;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;

import java.io.IOException;
import java.nio.file.Paths;

public class WriteNumericIndex {

    public static void main(String[] args) throws IOException {
        MMapDirectory mMapDirectory = new MMapDirectory(Paths.get("/opt/temp"));
        Analyzer analyzer = new NShortestPathAnalyzer(1);
        IndexConfig indexConfig = new IndexConfig(analyzer);
        IndexWriter indexWriter = new IndexWriter(indexConfig, mMapDirectory);
        Document doc = new Document();
        DoubleField field = new DoubleField("price", 12.5, Field.Tokenized.YES, Field.Stored.YES);
        doc.add(field);
        indexWriter.addDoc(doc);


//        Document doc2 = new Document();
//        DoubleField field2 = new DoubleField("price", 11.5, Field.Tokenized.YES, Field.Stored.YES);
//        doc2.add(field2);
//        indexWriter.addDoc(doc2);
//
//        Document doc3 = new Document();
//        DoubleField field3 = new DoubleField("price", 12.6, Field.Tokenized.YES, Field.Stored.YES);
//        doc3.add(field3);
//        indexWriter.addDoc(doc3);

        indexWriter.commit();
    }
}
