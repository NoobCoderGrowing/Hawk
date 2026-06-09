package hawk.benchmark;

import document.Document;
import hawk.indexer.writer.config.IndexConfig;
import hawk.recall.query.NumericRangeQuery;
import hawk.recall.query.Query;
import hawk.recall.query.StringQuery;
import hawk.recall.query.TermQuery;
import hawk.recall.search.ScoreDoc;
import hawk.recall.search.Searcher;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgs = {"-Xms2g", "-Xmx2g"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class HawkSearchBenchmark {

    private static final int INDEX_DOC_COUNT = 50000;
    private static final int QUERY_COUNT = 32;
    private static final int TOP_N = Integer.MAX_VALUE;

    private Searcher searcher;

    private Query[] termQueries;

    private Query[] stringQueries;

    private Query[] rangeQueries;

    private final AtomicInteger queryCursor = new AtomicInteger();

    @Setup(Level.Trial)
    public void buildIndexAndQueries() throws Exception {
        IndexConfig indexConfig = BenchmarkSupport.newIndexConfig();
        Path indexDir = BenchmarkSupport.indexDir().resolve("search");
        List<Document> documents = CorpusLoader.loadDocuments(INDEX_DOC_COUNT);
        BenchmarkSupport.buildIndex(indexDir, documents, indexConfig);
        searcher = BenchmarkSupport.openSearcher(indexDir, indexConfig);

        List<String> titles = CorpusLoader.sampleTitles(QUERY_COUNT);
        double[][] randomRanges = BenchmarkRangeQueries.buildRandomRanges(QUERY_COUNT);
        termQueries = new Query[QUERY_COUNT];
        stringQueries = new Query[QUERY_COUNT];
        rangeQueries = new Query[QUERY_COUNT];
        for (int i = 0; i < QUERY_COUNT; i++) {
            String title = titles.get(i);
            String firstTerm = title.substring(0, Math.min(2, title.length()));
            termQueries[i] = new TermQuery("title", firstTerm);
            stringQueries[i] = new StringQuery("title", firstTerm);
            rangeQueries[i] = new NumericRangeQuery("price", randomRanges[i][0], randomRanges[i][1]);
        }
    }

    @TearDown(Level.Trial)
    public void closeSearcher() {
        if (searcher != null) {
            searcher.close();
        }
    }

    private Query nextQuery(Query[] queries) {
        return queries[Math.floorMod(queryCursor.getAndIncrement(), queries.length)];
    }

    @Benchmark
    public void searchTerm(Blackhole blackhole) {
        ScoreDoc[] hits = searcher.search(nextQuery(termQueries), TOP_N);
        blackhole.consume(hits);
    }

    @Benchmark
    public void searchString(Blackhole blackhole) {
        ScoreDoc[] hits = searcher.search(nextQuery(stringQueries), TOP_N);
        blackhole.consume(hits);
    }

    @Benchmark
    public void searchNumericRange(Blackhole blackhole) {
        ScoreDoc[] hits = searcher.search(nextQuery(rangeQueries), TOP_N);
        blackhole.consume(hits);
    }

    @Benchmark
    public void searchTermAndFetchDoc(Blackhole blackhole) {
        ScoreDoc[] hits = searcher.search(nextQuery(termQueries), TOP_N);
        if (hits.length > 0) {
            Document document = searcher.doc(hits[0]);
            blackhole.consume(document);
        }
        blackhole.consume(hits);
    }
}
