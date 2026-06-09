package field;

import util.NumberUtil;

import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

@Data
public class PrimaryKeyField implements Field {

    private String name;
    private long value;
    @JsonDeserialize(as = Field.Tokenized.class)
    public Enum<Field.Tokenized> isTokenized;

    @JsonDeserialize(as = Field.Stored.class)
    public Enum<Field.Stored> isStored;

    public PrimaryKeyField(long value) {
        this.name = "uniqueID";
        this.value = value;
        this.isTokenized = Tokenized.NO;
        this.isStored = Stored.YES;
    }

    @Override
    public Enum<Tokenized> isTokenized() {
        return Tokenized.NO;
    }

    @Override
    public Enum<Stored> isStored() {
        return Stored.YES;
    }

    @Override
    public byte[] serializeName() {
        return name.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] customSerialize() {
        byte[] nameByte = name.getBytes(StandardCharsets.UTF_8);
        byte[] nameLength = NumberUtil.int2Vint(nameByte.length);
        byte[] valueByte = NumberUtil.long2Bytes(value);
        byte[] valueLength = new byte[]{0b00001000}; // 8-byte long, same as DoubleField
        byte[] result = new byte[nameByte.length + nameLength.length + valueByte.length + valueLength.length];
        int pos = 0;
        System.arraycopy(nameLength, 0, result, pos, nameLength.length);
        pos += nameLength.length;
        System.arraycopy(nameByte, 0, result, pos, nameByte.length);
        pos += nameByte.length;
        System.arraycopy(valueLength, 0, result, pos, valueLength.length);
        pos += valueLength.length;
        System.arraycopy(valueByte, 0, result, pos, valueByte.length);
        return result;
    }
}