package hawk.index.core.field;

import lombok.Data;

import java.nio.charset.StandardCharsets;

@Data
public class StringField extends Field{
    private String name;

    private String value;

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
        byte[] valueByte = value.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[nameByte.length + valueByte.length];
        System.arraycopy(nameByte, 0, result, 0, nameByte.length);
        System.arraycopy(valueByte, 0, result, nameByte.length, valueByte.length);
        return result;
    }
}
