package hawk.index.core.reader;

import lombok.Data;

@Data
public class FDXNode {

    private int key;

    private byte[] offset;

    public FDXNode(int key, byte[] offset) {
        this.key = key;
        this.offset = offset;
    }
}
