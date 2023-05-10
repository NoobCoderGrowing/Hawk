package field;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import util.NumberUtil;
import lombok.Data;

import java.nio.charset.StandardCharsets;

@Data
@JsonTypeInfo(use=JsonTypeInfo.Id.CLASS, include=JsonTypeInfo.As.PROPERTY, property="type")
@JsonTypeName("DoubleField")
public class DoubleField implements Field{

    @JsonDeserialize(as = Field.Tokenized.class)
    private Enum<Field.Tokenized> isTokenized;

    @JsonDeserialize(as = Field.Stored.class)
    private Enum<Field.Stored> isStored;

    private String name;

    private double value;

    public DoubleField(String name, double value) {
        this.name = name;
        this.value = value;
    }

    @JsonCreator
    public DoubleField(String name, double value, Enum<Field.Tokenized> isTokenized,
                       Enum<Field.Stored> isStored) {
        this.name = name;
        this.value = value;
        this.isTokenized = isTokenized;
        this.isStored = isStored;
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
