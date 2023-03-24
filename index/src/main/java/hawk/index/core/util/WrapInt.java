package hawk.index.core.util;

import lombok.Data;

@Data
public class WrapInt {

    private int value;

    public WrapInt(int value) {
        this.value = value;
    }
}
