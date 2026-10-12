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

    /**
     * 文档里**主键字段的名字**。{@code DocWriter} 靠它从 {@code Document} 里取主键（写 pk.map、查重）。
     *
     * <p>默认 {@code "uniqueID"} —— {@link field.PrimaryKeyField#PrimaryKeyField(long)} 这个便捷构造器
     * 给字段起的正是这个名字，现有各处的建索引入口（demo、benchmark）都按它来，
     * 所以默认值保证它们不受影响。
     *
     * <p>接别的数据源时（比如数据库的列叫 {@code id}），用三参构造器把名字传进来即可 ——
     * 以前这里写死 {@code "uniqueID"}，逼得数据源必须迁就这个名字。
     */
    private String primaryKeyFieldName = "uniqueID";

    public IndexConfig(Analyzer analyzer) {
        this(analyzer, Constants.PROCESSOR_NUM);
    }

    public IndexConfig(Analyzer analyzer, int indexerThreadNum) {
        this(analyzer, indexerThreadNum, "uniqueID");
    }

    public IndexConfig(Analyzer analyzer, int indexerThreadNum, String primaryKeyFieldName) {
        this.analyzer = analyzer;
        this.indexerThreadNum = indexerThreadNum;
        this.primaryKeyFieldName = primaryKeyFieldName;
    }
}
