package demo;

import directory.MMapDirectory;
import document.Document;
import field.DoubleField;
import field.Field;
import field.PrimaryKeyField;
import field.StringField;
import hawk.indexer.writer.IndexWriter;
import hawk.indexer.writer.config.IndexConfig;
import hawk.segment.core.anlyzer.Analyzer;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;


public class WriteIndexFromFile2 {

    private static final String CORPUS_FILE_PROPERTY = "hawk.benchmark.corpus.file";

    private static final Path DEFAULT_CORPUS_FILE = Paths.get("goods.csv");

    private static Path corpusFile() {
        String configured = System.getProperty(CORPUS_FILE_PROPERTY);
        return configured == null ? DEFAULT_CORPUS_FILE : Paths.get(configured);
    }

    public static void main(String[] args) throws Exception {
        MMapDirectory mMapDirectory = new MMapDirectory(Paths.get("/opt/index/1"));
        Analyzer analyzer = new NShortestPathAnalyzer(1);
        IndexConfig indexConfig = new IndexConfig(analyzer);
        IndexWriter indexWriter = new IndexWriter(indexConfig, mMapDirectory);
        try (BufferedReader bufferedReader = Files.newBufferedReader(corpusFile(), StandardCharsets.UTF_8)) {
            String line;
            long uniqueID = 200000;
            while ((line = bufferedReader.readLine()) != null) {
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
        }
        indexWriter.commit();
    }
}
