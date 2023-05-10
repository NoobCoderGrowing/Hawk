package demo;

import directory.MMapDirectory;
import document.Document;
import field.Field;
import field.StringField;
import writer.IndexWriter;
import writer.IndexConfig;
import hawk.segment.core.anlyzer.Analyzer;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;

import java.io.IOException;
import java.nio.file.Paths;

public class WirteIndex {

    public static void main(String[] args) throws IOException {
        MMapDirectory mMapDirectory = new MMapDirectory(Paths.get("/opt/temp/shard1"));
        Analyzer analyzer = new NShortestPathAnalyzer(1);
        IndexConfig indexConfig = new IndexConfig(analyzer);
        IndexWriter indexWriter = new IndexWriter(indexConfig, mMapDirectory);
        Document doc = new Document();
        StringField field = new StringField("title", "适用于丰田18-21款八代凯美瑞中控仪表台防晒隔热避光垫内饰改装", Field.Tokenized.YES, Field.Stored.YES);
        doc.add(field);
        indexWriter.addDoc(doc);


        Document doc2 = new Document();
        StringField field2 = new StringField("title", "适配丰田凯美瑞 亚洲龙 双擎混动版电池滤芯滤网", Field.Tokenized.YES, Field.Stored.YES);
        doc2.add(field2);
        indexWriter.addDoc(doc2);

        indexWriter.commit();
    }
}
