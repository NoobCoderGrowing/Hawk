package field;

import lombok.Data;
import lombok.NoArgsConstructor;
import util.NumberUtil;

import java.nio.charset.StandardCharsets;

/**
 * 长整数字段 —— 与 {@link NumberField}（double）并列的第二种数值字段。
 *
 * <h2>为什么需要它</h2>
 * {@code NumberField} 内部是 {@code double}，只有 52 位尾数，**超过 2^53 的整数存不准**：
 * <pre>
 *   9,007,199,254,740,993   → 存成 double 后 9,007,199,254,740,992
 *   1,234,567,890,123,456,789 → 1,234,567,890,123,456,800
 * </pre>
 * 纳秒时间戳、雪花 ID、大额精确整数都属于这一类。
 *
 * <p>本字段用 {@link NumberUtil#long2SortableLong} 编码（只翻符号位），
 * **无任何精度损失**，且对全部 64 位 long 保序。
 *
 * <p>两种数值字段在 fdm 里用**不同的类型位**区分（{@code NumberField} 是
 * {@code 0b00000100}，本类是 {@code 0b00100000}），所以查询侧能判断
 * "这个字段是 double 还是 long"，不会拿 {@code LongRangeQuery} 去查 double 字段。
 */
@Data
@NoArgsConstructor
public class LongField implements Field {

    /** 在 fdm 里的类型位。加新字段类型时同步改 {@code DocWriter#getFieldType} 与 {@code Searcher#createField}。 */
    public static final byte TYPE_BIT = 0b00100000;

    private Tokenized isTokenized;

    private Stored isStored;

    private String name;

    private long value;

    /** 字段定义式（无值）—— 请求体里描述"有这么个字段"。 */
    public LongField(String name, Tokenized isTokenized, Stored isStored) {
        this.name = name;
        this.isTokenized = isTokenized;
        this.isStored = isStored;
    }

    /** 值式。 */
    public LongField(String name, long value) {
        this.name = name;
        this.value = value;
        this.isTokenized = Tokenized.YES;
        this.isStored = Stored.YES;
    }

    public LongField(String name, Long value, Tokenized isTokenized, Stored isStored) {
        this.name = name;
        this.value = value == null ? 0L : value;
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
        byte[] valueByte = NumberUtil.long2Bytes(value);        // 8 字节，原样存 long
        byte[] valueLength = new byte[]{0b00001000};
        byte[] result = new byte[nameByte.length + valueByte.length + nameLength.length + valueLength.length];
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

    @Override
    public byte[] serializeName() {
        return this.name.getBytes(StandardCharsets.UTF_8);
    }
}
