package hawk.benchmark;

import document.Document;
import field.DoubleField;
import field.PrimaryKeyField;
import field.StringField;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class LuceneBenchmarkSupport {

    public static final String INDEX_DIR_PROPERTY = "lucene.benchmark.index.dir";

    private static final Path DEFAULT_INDEX_DIR = BenchmarkSupport.indexDir().getParent()
            .resolve("lucene-jmh-index");

    private LuceneBenchmarkSupport() {
    }

    public static Path indexDir() {
        String configured = System.getProperty(INDEX_DIR_PROPERTY);
        return configured == null ? DEFAULT_INDEX_DIR : java.nio.file.Paths.get(configured);
    }

    public static Analyzer newAnalyzer() {
        return new HawkLuceneAnalyzer(1);
    }

    public static IndexWriterConfig newBenchmarkWriterConfig() {
        IndexWriterConfig config = new IndexWriterConfig(newAnalyzer());
        config.setRAMBufferSizeMB(256);
        config.setUseCompoundFile(false);
        config.setMergeScheduler(new org.apache.lucene.index.ConcurrentMergeScheduler());
        return config;
    }

    public static org.apache.lucene.document.Document toLuceneDocument(Document hawkDocument) {
        PrimaryKeyField primaryKeyField = (PrimaryKeyField) hawkDocument.getFieldMap().get("uniqueID");
        StringField titleField = (StringField) hawkDocument.getFieldMap().get("title");
        DoubleField priceField = (DoubleField) hawkDocument.getFieldMap().get("price");
        StringField descriptField = (StringField) hawkDocument.getFieldMap().get("descript");
        DoubleField digtField = (DoubleField) hawkDocument.getFieldMap().get("digt");

        long uniqueId = primaryKeyField.getValue();
        String title = titleField.getValue();
        double price = priceField.getValue();
        String descript = descriptField.getValue();
        double digt = digtField.getValue();

        org.apache.lucene.document.Document document = new org.apache.lucene.document.Document();
        document.add(new LongPoint("uniqueID", uniqueId));
        document.add(new StoredField("uniqueID", uniqueId));
        document.add(new TextField("title", title, Field.Store.YES));
        document.add(new DoublePoint("price", price));
        document.add(new StoredField("price", price));
        document.add(new StoredField("descript", descript));
        document.add(new StoredField("digt", digt));
        return document;
    }

    public static void buildIndex(Path indexDir, List<Document> documents) throws IOException {
        BenchmarkSupport.wipeDirectory(indexDir);
        try (Directory directory = FSDirectory.open(indexDir);
             IndexWriter indexWriter = new IndexWriter(directory, newBenchmarkWriterConfig())) {
            for (Document hawkDocument : documents) {
                indexWriter.addDocument(toLuceneDocument(hawkDocument));
            }
            indexWriter.commit();
        }
    }

    public static IndexReader openReader(Path indexDir) throws IOException {
        Directory directory = FSDirectory.open(indexDir);
        return DirectoryReader.open(directory);
    }

    public static IndexSearcher openSearcher(Path indexDir) throws IOException {
        IndexReader reader = openReader(indexDir);
        IndexSearcher searcher = new IndexSearcher(reader);
        searcher.setSimilarity(new BM25Similarity());
        return searcher;
    }
}
