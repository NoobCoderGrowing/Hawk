package hawk.index.core.writer;

import hawk.segment.core.anlyzer.Analyzer;
import lombok.Data;

@Data
public class IndexWriterConfig {

    private Analyzer analyzer;

    private long maxRamUsage = 1024 * 1024 * 1024;// default 1GB

    public IndexWriterConfig(Analyzer analyzer) {
        this.analyzer = analyzer;
    }

    public IndexWriterConfig(Analyzer analyzer, long maxRamUsage) {
        this.analyzer = analyzer;
        this.maxRamUsage = maxRamUsage;
    }
}
