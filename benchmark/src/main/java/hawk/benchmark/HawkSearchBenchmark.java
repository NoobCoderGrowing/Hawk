package hawk.benchmark;

import document.Document;
import hawk.indexer.writer.config.IndexConfig;
import hawk.recall.query.BooleanQuery;
import hawk.recall.query.Query;
import hawk.recall.query.RangeQuery;
import hawk.recall.query.TermQuery;
import hawk.recall.request.StringQuery;
import hawk.recall.search.ScoreDoc;
import hawk.recall.search.Searcher;
import hawk.recall.search.TopDocs;
import hawk.segment.core.anlyzer.Analyzer;
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
    private static final int TOP_N = 500;

    private Searcher searcher;

    /** 建索引时用的分词器，searchString 基准要拿它现场分词。 */
    private Analyzer analyzer;

    private Query[] termQueries;

    /** 文本查询是"请求层"的对象，**不是** Query；检索前要先分词。 */
    private StringQuery[] stringQueries;

    private Query[] rangeQueries;

    private final AtomicInteger queryCursor = new AtomicInteger();

    private final AtomicInteger stringCursor = new AtomicInteger();

    @Setup(Level.Trial)
    public void buildIndexAndQueries() throws Exception {
        IndexConfig indexConfig = BenchmarkSupport.newIndexConfig();
        this.analyzer = indexConfig.getAnalyzer();
        Path indexDir = BenchmarkSupport.indexDir().resolve("search");
        List<Document> documents = CorpusLoader.loadDocuments(INDEX_DOC_COUNT);
        BenchmarkSupport.buildIndex(indexDir, documents, indexConfig);
        searcher = BenchmarkSupport.openSearcher(indexDir, indexConfig);

        List<String> titles = CorpusLoader.sampleTitles(QUERY_COUNT);
        double[][] randomRanges = BenchmarkRangeQueries.buildRandomRanges(QUERY_COUNT);
        termQueries = new Query[QUERY_COUNT];
        stringQueries = new StringQuery[QUERY_COUNT];
        rangeQueries = new Query[QUERY_COUNT];
        for (int i = 0; i < QUERY_COUNT; i++) {
            String title = titles.get(i);
            String firstTerm = title.substring(0, Math.min(2, title.length()));
            termQueries[i] = TermQuery.of("title", firstTerm);
            stringQueries[i] = new StringQuery("title", firstTerm);
            rangeQueries[i] = RangeQuery.closed("price", randomRanges[i][0], randomRanges[i][1]);
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
        TopDocs hits = searcher.search(nextQuery(termQueries), TOP_N);
        blackhole.consume(hits.scoreDocs());
    }

    /**
     * 文本查询：StringQuery 不再直接可检索，分词 + 组 BooleanQuery 由调用方做，
     * 所以这个基准测的仍然是「分词 + 组合 + 检索」的完整代价。
     */
    @Benchmark
    public void searchString(Blackhole blackhole) {
        StringQuery stringQuery =
                stringQueries[Math.floorMod(stringCursor.getAndIncrement(), stringQueries.length)];
        BooleanQuery.Builder builder = BooleanQuery.builder();
        for (TermQuery termQuery : stringQuery.getTermQueryList(analyzer)) {
            builder.must(termQuery);
        }
        BooleanQuery query = builder.build();
        TopDocs hits = searcher.search(query, TOP_N);
        blackhole.consume(hits.scoreDocs());
    }

    @Benchmark
    public void searchNumericRange(Blackhole blackhole) {
        TopDocs hits = searcher.search(nextQuery(rangeQueries), TOP_N);
        blackhole.consume(hits.scoreDocs());
    }

    @Benchmark
    public void searchTermAndFetchDoc(Blackhole blackhole) {
        TopDocs hits = searcher.search(nextQuery(termQueries), TOP_N);
        List<ScoreDoc> scoreDocs = hits.scoreDocs();
        if (!scoreDocs.isEmpty()) {
            Document document = searcher.doc(scoreDocs.get(0));
            blackhole.consume(document);
        }
        blackhole.consume(scoreDocs);
    }
}
