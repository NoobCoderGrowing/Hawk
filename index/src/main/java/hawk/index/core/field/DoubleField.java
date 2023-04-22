package hawk.index.core.field;
import hawk.index.core.util.NumberUtil;
import lombok.Data;

import java.nio.charset.StandardCharsets;

@Data
public class DoubleField extends Field{

    private String name;

    private double value;

    public DoubleField(String name, double value) {
        this.name = name;
        this.value = value;
    }

    public DoubleField(String name, double value, Enum<Field.Tokenized> isTokenized,
                       Enum<Field.Stored> isStored) {
        this.name = name;
        this.value = value;
        this.isTokenized = isTokenized;
        this.isStored = isStored;
    }

    @Override
    public byte[] serialize() {
        byte[] nameByte = name.getBytes(StandardCharsets.UTF_8);
        byte[] nameLength = NumberUtil.int2Vint(nameByte.length);
        byte[] valueByte = NumberUtil.long2Bytes(Double.doubleToLongBits(value));
        byte[] valueLength = new byte[]{0b00001000};
        byte[] result = new byte[nameByte.length + valueByte.length + nameLength.length + valueLength.length];
        int pos = 0;
        System.arraycopy(nameLength, 0 ,result, pos, nameLength.length);
        pos += nameLength.length;
        System.arraycopy(nameByte,0, result, pos, nameByte.length);
        pos += nameByte.length;
        System.arraycopy(valueLength, 0, result, pos, valueLength.length );
        pos += valueLength.length;
        System.arraycopy(valueByte, 0, result ,pos, valueByte.length);
        return result;
    }

    @Override
    public byte[] serializeName() {
        return this.name.getBytes(StandardCharsets.UTF_8);
    }
}
