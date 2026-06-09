package common;

import lombok.Data;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

@Data
public class IndexFormatConfig {

    private LZ4Factory factory = LZ4Factory.fastestInstance();

    private LZ4Compressor compressor = factory.fastCompressor();

    private LZ4FastDecompressor decompressor = factory.fastDecompressor();

    private int blocSize = 16 * 1024;

    @Deprecated
    private int precisionStep = 4;

    private int bkdMaxPointsInLeaf = 512;
}
