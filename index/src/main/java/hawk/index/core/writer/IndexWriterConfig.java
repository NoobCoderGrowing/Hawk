package hawk.index.core.writer;

import hawk.segment.core.anlyzer.Analyzer;

public class IndexWriterConfig {

    private Analyzer analyzer;

    public IndexWriterConfig(Analyzer analyzer) {
        this.analyzer = analyzer;
    }
}
