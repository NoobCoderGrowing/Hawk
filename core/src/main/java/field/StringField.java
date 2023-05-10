package field;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import util.NumberUtil;
import lombok.Data;

import java.nio.charset.StandardCharsets;

@Data
@JsonTypeInfo(use=JsonTypeInfo.Id.CLASS, include= JsonTypeInfo.As.PROPERTY, property="@class")
@JsonTypeName("StringField")
public class StringField implements Field{

    @JsonDeserialize(as = Field.Tokenized.class)
    public Enum<Field.Tokenized> isTokenized;

    @JsonDeserialize(as = Field.Stored.class)
    public Enum<Field.Stored> isStored;

    private String name;

    private String value;

    public StringField(String name, String value) {
        this.name = name;
        this.value = value;
    }

    @JsonCreator
    public StringField(String name, String value, Enum<Field.Tokenized> isTokenized,
                       Enum<Field.Stored> isStored) {
        this.name = name;
        this.value = value;
        this.isTokenized = isTokenized;
        this.isStored = isStored;
    }

    public byte[] serializeName(){
        return this.name.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Enum<Tokenized> isTokenized() {
        return isTokenized;
    }

    @Override
    public Enum<Stored> isStored() {
        return isStored;
    }

    @Override
    public byte[] customSerialize() {
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
