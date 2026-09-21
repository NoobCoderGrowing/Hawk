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

    private boolean enableMerge = true;

    /** 默认的单次刷盘内存预算，8 GiB。
     *  必须是 long 字面量：8589934592 已超出 int 范围，写成 int 会静默溢出成负数。 */
    public static final long DEFAULT_MAX_RAM_USAGE = 8L * 1024 * 1024 * 1024;

    public IndexConfig(Analyzer analyzer) {
        this(analyzer, DEFAULT_MAX_RAM_USAGE, Constants.PROCESSOR_NUM);
    }

    public IndexConfig(Analyzer analyzer, long maxRamUsage) {
        this(analyzer, maxRamUsage, Constants.PROCESSOR_NUM);
    }

    public IndexConfig(Analyzer analyzer, int indexerThreadNum) {
        this(analyzer, DEFAULT_MAX_RAM_USAGE, indexerThreadNum);
    }

    public IndexConfig(Analyzer analyzer, long maxRamUsage, int indexerThreadNum) {
        this.analyzer = analyzer;
        this.maxRamUsage = maxRamUsage;
        this.indexerThreadNum = indexerThreadNum;
    }
}
