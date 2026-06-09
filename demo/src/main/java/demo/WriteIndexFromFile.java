package demo;

import directory.MMapDirectory;
import document.Document;
import field.DoubleField;
import field.Field;
import field.StringField;
import hawk.indexer.writer.IndexWriter;
import hawk.indexer.writer.config.IndexConfig;
import hawk.segment.core.anlyzer.Analyzer;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Paths;
import field.PrimaryKeyField;


public class WriteIndexFromFile {

    public static void main(String[] args) throws IOException {
        MMapDirectory mMapDirectory = new MMapDirectory(Paths.get("/opt/index/1"));
        Analyzer analyzer = new NShortestPathAnalyzer(1);
        IndexConfig indexConfig = new IndexConfig(analyzer);
        IndexWriter indexWriter = new IndexWriter(indexConfig, mMapDirectory);
        URL resource = ClassLoader.getSystemResource("goods-short.csv");
        String path = resource.getPath();
        BufferedReader bufferedReader = new BufferedReader(new FileReader(path));
        String line;
        long uniqueID = 0;
        while ((line = bufferedReader.readLine()) != null){
            String[] strings = line.split("\t");
            String title = strings[1];
            float price = Float.parseFloat(strings[2]);
            double doublePrice = (double) price;
            Document document = new Document();
            PrimaryKeyField primaryKeyField = new PrimaryKeyField(uniqueID);
            StringField stringField = new StringField("title", title, Field.Tokenized.YES, Field.Stored.YES);
            DoubleField doubleField = new DoubleField("price", doublePrice, Field.Tokenized.YES, Field.Stored.YES);
            StringField description = new StringField("descript", title, Field.Tokenized.NO, Field.Stored.YES);
            DoubleField priceField = new DoubleField("digt", doublePrice, Field.Tokenized.NO, Field.Stored.YES);
            document.add(primaryKeyField);
            document.add(stringField);
            document.add(doubleField);
            document.add(description);
            document.add(priceField);
            indexWriter.addDoc(document);
            uniqueID++;
        }
        bufferedReader.close();
        indexWriter.commit();
    }
}
