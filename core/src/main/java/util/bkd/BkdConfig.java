package util.bkd;

import common.IndexFormatConfig;
import lombok.Data;

@Data
public class BkdConfig {

    private int maxPointsInLeaf = 512;

    public BkdConfig() {
    }

    public BkdConfig(IndexFormatConfig indexFormatConfig) {
        this.maxPointsInLeaf = indexFormatConfig.getBkdMaxPointsInLeaf();
    }
}
