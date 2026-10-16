package hawk.indexer.writer.config;

import common.IndexFormatConfig;
import directory.Constants;
import hawk.segment.core.anlyzer.TokenizerRegistry;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
public class IndexConfig extends IndexFormatConfig {

    /**
     * 字段名 → 该字段用的 tokenizer 名（**有序**，决定并集时的顺序）。
     *
     * <p>名字来自 {@link TokenizerRegistry} 的名字空间（今天只有 {@code nshortest-<正整数>}）。
     * 存名字而不是实例：这些名字要**逐字写进 fdm**（每个字段记一份），召回端读出来再送回注册表解析。
     *
     * <p>只对**真被分词**的字段有意义（{@code isIndexed=YES} 的 StringField）。数值字段和
     * 只存原文的字段不会被分词，它们的记录里是空列表。
     */
    private Map<String, List<String>> fieldTokenizers = new HashMap<>();

    /**
     * 没在 {@link #fieldTokenizers} 里出现的字段用这一组。
     *
     * <p>默认 {@link TokenizerRegistry#DEFAULT} —— 与加这个能力之前的行为一致，
     * 所以直接 new {@code IndexConfig()} 的老调用方（demo 等）不受影响。
     * 与 {@link #primaryKeyFieldName} 的默认值是同一个套路：一个默认常量让老调用方不用改。
     */
    private List<String> defaultTokenizers = List.of(TokenizerRegistry.DEFAULT);

    private int indexerThreadNum;

    private boolean enableMerge = false;

    /**
     * 文档里**主键字段的名字**。{@code DocWriter} 靠它从 {@code Document} 里取主键（写 pk.map、查重）。
     *
     * <p>默认 {@code "uniqueID"} —— {@link field.PrimaryKeyField#PrimaryKeyField(long)} 这个便捷构造器
     * 给字段起的正是这个名字，现有各处的建索引入口（demo、cloud-indexer）都按它来，
     * 所以默认值保证它们不受影响。
     *
     * <p>接别的数据源时（比如数据库的列叫 {@code id}），用二参构造器把名字传进来即可 ——
     * 以前这里写死 {@code "uniqueID"}，逼得数据源必须迁就这个名字。
     */
    private String primaryKeyFieldName = "uniqueID";

    public IndexConfig() {
        this(Constants.PROCESSOR_NUM);
    }

    public IndexConfig(int indexerThreadNum) {
        this(indexerThreadNum, "uniqueID");
    }

    public IndexConfig(int indexerThreadNum, String primaryKeyFieldName) {
        this.indexerThreadNum = indexerThreadNum;
        this.primaryKeyFieldName = primaryKeyFieldName;
    }

    /**
     * 该字段实际要用的 tokenizer 名。配置里没有这个字段就退回 {@link #defaultTokenizers}。
     *
     * <p>返回的列表**永不为空** —— 有默认兜底，所以调用方不必判空。这也是召回端"字段在索引里
     * 却读不到 tokenizer 组"该当成 bug 抛错的前提。
     */
    public List<String> tokenizersOf(String fieldName) {
        List<String> configured = fieldTokenizers.get(fieldName);
        return configured == null ? defaultTokenizers : configured;
    }
}
