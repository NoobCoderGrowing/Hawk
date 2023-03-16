package hawk.index.core.writer;

import lombok.Data;

@Data
public class FieldTermPair {

    private byte[] field;

    private byte[] term;

    public FieldTermPair(byte[] field, byte[] term) {
        this.field = field;
        this.term = term;
    }
}
