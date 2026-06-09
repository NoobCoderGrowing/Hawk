package hawk.benchmark;

import hawk.segment.core.anlyzer.Analyzer;
import hawk.segment.core.anlyzer.NShortestPathAnalyzer;
import org.apache.lucene.analysis.Analyzer.TokenStreamComponents;

/**
 * Lucene analyzer adapter that delegates tokenization to Hawk's {@link NShortestPathAnalyzer}.
 */
public final class HawkLuceneAnalyzer extends org.apache.lucene.analysis.Analyzer {

    private final Analyzer hawkAnalyzer;

    public HawkLuceneAnalyzer(int shortestPathCount) {
        this.hawkAnalyzer = new NShortestPathAnalyzer(shortestPathCount);
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        return new TokenStreamComponents(new HawkTokenizer(hawkAnalyzer, fieldName));
    }
}
