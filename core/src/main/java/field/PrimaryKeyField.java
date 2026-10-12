package field;

import util.NumberUtil;

import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Data;

@Data
public class PrimaryKeyField implements Field {

    private String name;
    private long value;

    public Tokenized isTokenized;

    public Stored isStored;


    public PrimaryKeyField(String name, Tokenized isTokenized, Stored isStored) {
        this.name = name;
        this.isTokenized = isTokenized;
        this.isStored = isStored;
    }

    /**
     * Jackson 用的构造器。
     *
     * <p>原先这个类**没有任何 Creator 注解**，而 Lombok 的 {@code @Data} 在已有显式构造器时
     * 不会再生无参构造器，于是 Jackson 一个可用的构造器都挑不出来，报
     * "cannot deserialize from Object value (no delegate- or property-based Creator)"。
     *
     * <p>{@code value} 用装箱的 {@code Long}：字段定义式的请求体不带 value，基本类型接不住 null。
     */
    @JsonCreator
    public PrimaryKeyField(String name, Long value, Tokenized isTokenized, Stored isStored) {
        this.name = name;
        this.value = value == null ? 0L : value;
        this.isTokenized = isTokenized;
        this.isStored = isStored;
    }

    public PrimaryKeyField(long value) {
        this.name = "uniqueID";
        this.value = value;
        this.isTokenized = Tokenized.NO;
        this.isStored = Stored.YES;
    }

    @Override
    public Tokenized isTokenized() {
        return Tokenized.NO;
    }

    @Override
    public Stored isStored() {
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
        byte[] valueLength = new byte[]{0b00001000}; // 8-byte long, same as NumberField
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
