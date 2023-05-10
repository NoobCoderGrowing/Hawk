package demo;

import directory.MMapDirectory;
import document.Document;
import field.DoubleField;
import field.Field;
import field.StringField;
import hawk.segment.core.anlyzer.Analyzer;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;
import writer.IndexConfig;
import writer.IndexWriter;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Paths;


public class WriteIndexFromFile {

    public static void main(String[] args) throws IOException {
        MMapDirectory mMapDirectory = new MMapDirectory(Paths.get("/opt/index/shard3"));
        Analyzer analyzer = new NShortestPathAnalyzer(1);
        IndexConfig indexConfig = new IndexConfig(analyzer);
        IndexWriter indexWriter = new IndexWriter(indexConfig, mMapDirectory);
        URL resource = ClassLoader.getSystemResource("goods-short.csv");
        String path = resource.getPath();
        BufferedReader bufferedReader = new BufferedReader(new FileReader(path));
        String line;
        while ((line = bufferedReader.readLine()) != null){
            String[] strings = line.split("\t");
            String title = strings[1];
            float price = Float.parseFloat(strings[2]);
            double doublePrice = (double) price;
            Document document = new Document();
            StringField stringField = new StringField("title", title, Field.Tokenized.YES, Field.Stored.YES);
            DoubleField doubleField = new DoubleField("price", doublePrice, Field.Tokenized.YES, Field.Stored.YES);
            document.add(stringField);
            document.add(doubleField);
            indexWriter.addDoc(document);
        }
        bufferedReader.close();
        indexWriter.commit();
    }
}
