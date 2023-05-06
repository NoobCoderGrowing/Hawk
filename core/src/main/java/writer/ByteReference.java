package writer;

import lombok.Data;

import java.util.Arrays;

@Data
public class ByteReference {

    private byte[] bytes;

    public ByteReference(byte[] bytes) {
        this.bytes = bytes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ByteReference that = (ByteReference) o;
        return Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
