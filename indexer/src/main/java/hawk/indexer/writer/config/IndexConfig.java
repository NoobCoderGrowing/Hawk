package hawk.indexer.writer.config;

import common.IndexFormatConfig;
import directory.Constants;
import hawk.segment.core.anlyzer.Analyzer;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class IndexConfig extends IndexFormatConfig {

    private Analyzer analyzer;

    private long maxRamUsage;

    private int indexerThreadNum;

    public IndexConfig(Analyzer analyzer) {
        this(analyzer, 1024 * 1024 * 1024L, Constants.PROCESSOR_NUM);
    }

    public IndexConfig(Analyzer analyzer, long maxRamUsage) {
        this(analyzer, maxRamUsage, Constants.PROCESSOR_NUM);
    }

    public IndexConfig(Analyzer analyzer, int indexerThreadNum) {
        this(analyzer, 1024 * 1024 * 1024, indexerThreadNum);
    }

    public IndexConfig(Analyzer analyzer, long maxRamUsage, int indexerThreadNum) {
        this.analyzer = analyzer;
        this.maxRamUsage = maxRamUsage;
        this.indexerThreadNum = indexerThreadNum;
    }
}
