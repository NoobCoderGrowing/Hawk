package demo;

import directory.MMapDirectory;
import document.Document;
import field.Field;
import field.StringField;
import hawk.indexer.writer.IndexWriter;
import hawk.indexer.writer.config.IndexConfig;
import hawk.segment.core.anlyzer.Analyzer;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;

import field.PrimaryKeyField;
import java.io.IOException;
import java.nio.file.Paths;

public class WirteIndex {

    public static void main(String[] args) throws Exception {
        MMapDirectory mMapDirectory = new MMapDirectory(Paths.get("/opt/index/1"));
        Analyzer analyzer = new NShortestPathAnalyzer(1);
        IndexConfig indexConfig = new IndexConfig(analyzer);
        IndexWriter indexWriter = new IndexWriter(indexConfig, mMapDirectory);
        Document doc = new Document();
        PrimaryKeyField primaryKeyField = new PrimaryKeyField(0);
        doc.add(primaryKeyField);
        StringField field = new StringField("title", "适用于丰田18-21款八代凯美瑞中控仪表台防晒隔热避光垫内饰改装", Field.Tokenized.YES, Field.Stored.YES);
        doc.add(field);
        indexWriter.addDoc(doc);


        Document doc2 = new Document();
        PrimaryKeyField primaryKeyField2 = new PrimaryKeyField(1);
        doc2.add(primaryKeyField2);
        StringField field2 = new StringField("title", "适配丰田凯美瑞 亚洲龙 双擎混动版电池滤芯滤网", Field.Tokenized.YES, Field.Stored.YES);
        doc2.add(field2);
        indexWriter.addDoc(doc2);

        indexWriter.commit();
    }
}
