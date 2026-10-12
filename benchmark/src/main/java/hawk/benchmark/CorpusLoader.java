package hawk.benchmark;

import document.Document;
import field.NumberField;
import field.Field;
import field.PrimaryKeyField;
import field.StringField;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public final class CorpusLoader {

    public static final String CORPUS_FILE_PROPERTY = "hawk.benchmark.corpus.file";

    private static final Path DEFAULT_CORPUS_FILE = Paths.get("goods.csv");

    private CorpusLoader() {
    }

    public static Path corpusFile() {
        String configured = System.getProperty(CORPUS_FILE_PROPERTY);
        return configured == null ? DEFAULT_CORPUS_FILE : Paths.get(configured);
    }

    public static List<Document> loadDocuments(int maxDocs) throws IOException {
        List<Document> documents = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(corpusFile(), StandardCharsets.UTF_8)) {
            String line;
            long uniqueID = 0;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine) {
                    firstLine = false;
                    if (isHeader(line)) {
                        continue;
                    }
                }
                if (maxDocs > 0 && documents.size() >= maxDocs) {
                    break;
                }
                documents.add(parseLine(line, uniqueID++));
            }
        }
        return documents;
    }

    public static List<String> sampleTitles(int count) throws IOException {
        List<String> titles = new ArrayList<>(count);
        try (BufferedReader reader = Files.newBufferedReader(corpusFile(), StandardCharsets.UTF_8)) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null && titles.size() < count) {
                if (firstLine) {
                    firstLine = false;
                    if (isHeader(line)) {
                        continue;
                    }
                }
                String[] parts = line.split("\t", 3);
                if (parts.length >= 2) {
                    titles.add(parts[1]);
                }
            }
        }
        return titles;
    }

    /**
     * 首行是否表头。goods.csv 是 {@code uniqueID\ttitle\tprice}。
     *
     * <p>只在**确实像表头**时才跳：硬跳首行的话，哪天表头被去掉，会静默吃掉第一条数据 ——
     * 不报错，只是少一条文档。
     */
    private static boolean isHeader(String line) {
        return line.startsWith("uniqueID\t");
    }

    static Document parseLine(String line, long uniqueID) {
        String[] parts = line.split("\t", 3);
        String title = parts[1];
        double price = Double.parseDouble(parts[2]);

        Document document = new Document();
        document.add(new PrimaryKeyField(uniqueID));
        document.add(new StringField("title", title, Field.Tokenized.YES, Field.Stored.YES));
        document.add(new NumberField("price", price, Field.Tokenized.YES, Field.Stored.YES));
        document.add(new StringField("descript", title, Field.Tokenized.NO, Field.Stored.YES));
        document.add(new NumberField("digt", price, Field.Tokenized.NO, Field.Stored.YES));
        return document;
    }
}
