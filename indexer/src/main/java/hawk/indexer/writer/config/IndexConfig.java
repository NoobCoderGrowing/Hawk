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

    private int indexerThreadNum;

    private boolean enableMerge = false;

    public IndexConfig(Analyzer analyzer) {
        this(analyzer, Constants.PROCESSOR_NUM);
    }

    public IndexConfig(Analyzer analyzer, int indexerThreadNum) {
        this.analyzer = analyzer;
        this.indexerThreadNum = indexerThreadNum;
    }
}
