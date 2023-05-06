package writer;

import lombok.Data;
import java.util.Arrays;

@Data
public class PrefixedNumber {
    byte[] value;

    public PrefixedNumber(byte[] value) {
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PrefixedNumber that = (PrefixedNumber) o;
        return Arrays.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }
}
