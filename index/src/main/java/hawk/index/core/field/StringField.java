package hawk.index.core.field;

import hawk.index.core.util.NumberUtil;
import hawk.index.core.writer.DataOutput;
import lombok.Data;

import java.nio.charset.StandardCharsets;

@Data
public class StringField extends Field{
    private String name;

    private String value;

    public StringField(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public StringField(String name, String value, Enum<Field.Tokenized> isTokenized,
                       Enum<Field.Stored> isStored) {
        this.name = name;
        this.value = value;
        this.isTokenized = isTokenized;
        this.isStored = isStored;
    }

    public byte[] getNameBytes(){
        return this.name.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] getBytes() {
        byte[] nameByte = name.getBytes(StandardCharsets.UTF_8);
        byte[] nameLength = NumberUtil.int2Vint(nameByte.length);
        byte[] valueByte = value.getBytes(StandardCharsets.UTF_8);
        byte[] valueLength = NumberUtil.int2Vint(valueByte.length);
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
}
