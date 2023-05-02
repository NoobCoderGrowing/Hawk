package util;

import lombok.Data;

@Data
public class WrapLong {

    private long value;

    public WrapLong(long value) {
        this.value = value;
    }
}
