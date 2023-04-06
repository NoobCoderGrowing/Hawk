package hawk.index.core.field;
import hawk.index.core.util.NumberUtil;
import lombok.Data;

import java.nio.charset.StandardCharsets;

@Data
public class DoubleField extends Field{

    private String name;

    private double value;

    public DoubleField(String name, double value, Enum<Field.Tokenized> isTokenized,
                       Enum<Field.Stored> isStored) {
        this.name = name;
        this.value = value;
        this.isTokenized = isTokenized;
        this.isStored = isStored;
    }

    @Override
    public byte[] getBytes() {
        byte[] nameByte = name.getBytes(StandardCharsets.UTF_8);
        byte[] valueByte = NumberUtil.long2Bytes(Double.doubleToLongBits(value));
        byte[] result = new byte[nameByte.length + valueByte.length];
        System.arraycopy(nameByte, 0, result, 0, nameByte.length);
        System.arraycopy(valueByte, 0, result, nameByte.length, valueByte.length);
        return result;
    }

    public byte[] getNameBytes(){
        return this.name.getBytes(StandardCharsets.UTF_8);
    }
}
