package hawk.benchmark;

import document.Document;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgs = {"-Xms2g", "-Xmx2g"})
@Warmup(iterations = 1)
@Measurement(iterations = 3)
public class LuceneIndexBenchmark {

    @Param({"1000", "5000", "10000"})
    public int docCount;

    private List<Document> documents;

    private Path indexDir;

    @Setup(Level.Trial)
    public void loadCorpus() throws Exception {
        documents = CorpusLoader.loadDocuments(docCount);
        indexDir = LuceneBenchmarkSupport.indexDir().resolve("index-" + docCount);
        LuceneBenchmarkSupport.logIndexThreadConfig(LuceneBenchmarkSupport.newBenchmarkWriterConfig());
    }

    @Setup(Level.Iteration)
    public void prepareIndexDirectory() throws Exception {
        BenchmarkSupport.wipeDirectory(indexDir);
    }

    @Benchmark
    public void indexDocuments() throws Exception {
        try (Directory directory = FSDirectory.open(indexDir);
             IndexWriter indexWriter = new IndexWriter(directory, LuceneBenchmarkSupport.newBenchmarkWriterConfig())) {
            for (Document document : documents) {
                indexWriter.addDocument(LuceneBenchmarkSupport.toLuceneDocument(document));
            }
            indexWriter.commit();
        }
    }
}
