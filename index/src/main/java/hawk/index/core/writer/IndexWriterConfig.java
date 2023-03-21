package hawk.index.core.writer;

import hawk.index.core.directory.Constants;
import hawk.segment.core.anlyzer.Analyzer;
import lombok.Data;

@Data
public class IndexWriterConfig {

    private Analyzer analyzer;

    private long maxRamUsage;

    private int indexerThreadNum;

    public IndexWriterConfig(Analyzer analyzer) {
        // default 1GB
        // default core num according to hardware
        this(analyzer, 1024 * 1024 * 1024, Constants.PROCESSOR_NUM);
    }

    public IndexWriterConfig(Analyzer analyzer, long maxRamUsage) {
        this(analyzer, maxRamUsage, Constants.PROCESSOR_NUM);
    }

    public IndexWriterConfig(Analyzer analyzer, int indexerThreadNum) {
        this(analyzer, 1024 * 1024 * 1024, indexerThreadNum);
    }

    public IndexWriterConfig(Analyzer analyzer, long maxRamUsage, int indexerThreadNum) {
        this.analyzer = analyzer;
        this.maxRamUsage = maxRamUsage;
        this.indexerThreadNum = indexerThreadNum;
    }
}
