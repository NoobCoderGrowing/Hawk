package field;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Data;
import util.NumberUtil;

import java.nio.charset.StandardCharsets;

@Data
public class NumberField implements Field {

    private Tokenized isTokenized;

    private Stored isStored;

    private String name;

    private double value;

    public NumberField(String name, Tokenized isTokenized, Stored isStored) {
        this.name = name;
        this.isTokenized = isTokenized;
        this.isStored = isStored;
    }

    public NumberField(String name, double value) {
        this.name = name;
        this.value = value;
    }

    /**
     * Jackson 用的构造器。{@code value} 取装箱类型 {@code Double} 而不是基本类型：字段**定义**式的
     * 请求体（只有 name / isTokenized / isStored，没有 value）里这个键会缺失，基本类型接不住 null，
     * 会报 "Cannot map `null` into type `double`"。装箱后缺省填 0。
     */
    @JsonCreator
    public NumberField(String name, Double value, Tokenized isTokenized, Stored isStored) {
        this.name = name;
        this.value = value == null ? 0.0 : value;
        this.isTokenized = isTokenized;
        this.isStored = isStored;
    }

    @Override
    public Tokenized isTokenized() {
        return isTokenized;
    }

    @Override
    public Stored isStored() {
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
