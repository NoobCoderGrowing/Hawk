package hawk.index.core.writer;

import lombok.Data;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;

@Data
public class FieldTermPair {

    private byte[] field;

    private byte[] term;


    public FieldTermPair(byte[] field, byte[] term) {
        this.field = field;
        this.term = term;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FieldTermPair that = (FieldTermPair) o;
        return Arrays.equals(field, that.field) && Arrays.equals(term, that.term);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(field);
        result = 31 * result + Arrays.hashCode(term);
        return result;
    }

    public static void main(String[] args) {

    }
}
