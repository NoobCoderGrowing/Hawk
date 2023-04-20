package hawk.index.core.writer;

import hawk.index.core.directory.Constants;
import hawk.segment.core.anlyzer.Analyzer;
import lombok.Data;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

@Data
public class IndexConfig {

    private Analyzer analyzer;

    private long maxRamUsage;

    private int indexerThreadNum;

    private LZ4Factory factory = LZ4Factory.fastestInstance();

    private LZ4Compressor compressor = factory.fastCompressor();

    private LZ4FastDecompressor decompressor = factory.fastDecompressor();

    private int blocSize = 16 * 1024;

    private int precisionStep = 4;

    public IndexConfig(Analyzer analyzer) {
        // default 1GB
        // default core num according to hardware
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
