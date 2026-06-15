package hawk.benchmark;

import document.Document;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.similarities.BM25Similarity;
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
public class LuceneSearchBenchmark {

    private static final int INDEX_DOC_COUNT = 50000;
    private static final int QUERY_COUNT = 32;
    private static final int TOP_N = 500;

    private IndexReader indexReader;

    private IndexSearcher searcher;

    private Query[] termQueries;

    private Query[] stringQueries;

    private Query[] rangeQueries;

    private final AtomicInteger queryCursor = new AtomicInteger();

    @Setup(Level.Trial)
    public void buildIndexAndQueries() throws Exception {
        Path indexDir = LuceneBenchmarkSupport.indexDir().resolve("search");
        List<Document> documents = CorpusLoader.loadDocuments(INDEX_DOC_COUNT);
        LuceneBenchmarkSupport.buildIndex(indexDir, documents);
        indexReader = LuceneBenchmarkSupport.openReader(indexDir);
        searcher = new IndexSearcher(indexReader);
        searcher.setSimilarity(new BM25Similarity());

        List<String> titles = CorpusLoader.sampleTitles(QUERY_COUNT);
        double[][] randomRanges = BenchmarkRangeQueries.buildRandomRanges(QUERY_COUNT);
        termQueries = new Query[QUERY_COUNT];
        stringQueries = new Query[QUERY_COUNT];
        rangeQueries = new Query[QUERY_COUNT];
        for (int i = 0; i < QUERY_COUNT; i++) {
            String title = titles.get(i);
            String firstTerm = title.substring(0, Math.min(2, title.length()));
            termQueries[i] = new TermQuery(new Term("title", firstTerm));
            stringQueries[i] = buildStringQuery(firstTerm);
            rangeQueries[i] = DoublePoint.newRangeQuery("price", randomRanges[i][0], randomRanges[i][1]);
        }
    }

    @TearDown(Level.Trial)
    public void closeReader() throws Exception {
        if (indexReader != null) {
            indexReader.close();
        }
    }

    private Query buildStringQuery(String queryText) throws Exception {
        Analyzer analyzer = LuceneBenchmarkSupport.newAnalyzer();
        BooleanQuery.Builder andBuilder = new BooleanQuery.Builder();
        BooleanQuery.Builder orBuilder = new BooleanQuery.Builder();
        try (TokenStream tokenStream = analyzer.tokenStream("title", queryText)) {
            CharTermAttribute termAttribute = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                Query termQuery = new TermQuery(new Term("title", termAttribute.toString()));
                andBuilder.add(termQuery, BooleanClause.Occur.MUST);
                orBuilder.add(termQuery, BooleanClause.Occur.SHOULD);
            }
            tokenStream.end();
        }
        BooleanQuery andQuery = andBuilder.build();
        if (!andQuery.clauses().isEmpty()) {
            return andQuery;
        }
        return orBuilder.build();
    }

    private Query nextQuery(Query[] queries) {
        return queries[Math.floorMod(queryCursor.getAndIncrement(), queries.length)];
    }

    @Benchmark
    public void searchTerm(Blackhole blackhole) throws Exception {
        TopDocs hits = searcher.search(nextQuery(termQueries), TOP_N);
        blackhole.consume(hits.scoreDocs);
    }

    @Benchmark
    public void searchString(Blackhole blackhole) throws Exception {
        TopDocs hits = searcher.search(nextQuery(stringQueries), TOP_N);
        blackhole.consume(hits.scoreDocs);
    }

    @Benchmark
    public void searchNumericRange(Blackhole blackhole) throws Exception {
        TopDocs hits = searcher.search(nextQuery(rangeQueries), TOP_N);
        blackhole.consume(hits.scoreDocs);
    }

    @Benchmark
    public void searchTermAndFetchDoc(Blackhole blackhole) throws Exception {
        TopDocs hits = searcher.search(nextQuery(termQueries), TOP_N);
        if (hits.scoreDocs.length > 0) {
            org.apache.lucene.document.Document document = searcher.doc(hits.scoreDocs[0].doc);
            blackhole.consume(document);
        }
        blackhole.consume(hits.scoreDocs);
    }
}
