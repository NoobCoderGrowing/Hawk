package field;

import util.DataInput;
import util.NumberUtil;

import java.nio.charset.StandardCharsets;

/**
 * 字段类型的**唯一登记处** —— 类型位、实现类、JSON 名、各种转换，全在这里。
 *
 * <h2>为什么要有它</h2>
 * 加一种字段类型，本来要同时改六处：
 * <pre>
 *   DocWriter.getFieldType()           类     → 类型位
 *   DocWriter.storedOnlyFieldLength()  类     → 存储字节长度
 *   DocWriter.processIndexedField()    类     → 怎么建倒排 / 怎么编成 BKD 点
 *   Searcher.createField()             类型位 → 类（从存储字节解回来）
 *   TextIndexController 的白名单        JSON名 → 类（反序列化）
 *   IndexService.toField()             定义 + 原始值 → 类
 * </pre>
 * 全靠人记得，漏一处就是**静默出错**（字段被悄悄丢掉、或类型位认不出）。
 * 加 {@link LongField} 时就漏了 cloud-indexer 那两处 —— 本类把这些收成一处。
 *
 * <h2>类型位</h2>
 * fdm 里每个字段一个字节，位含义：
 * <pre>
 *   bit0 Stored.YES     bit1 Tokenized.YES
 *   bit2 NumberField    bit3 StringField    bit4 PrimaryKeyField    bit5 LongField
 * </pre>
 * 低两位与字段类型无关，所以 {@link #byBit} 要先掩掉。
 */
public enum FieldType {

    NUMBER("NumberField",      0b00000100),
    STRING("StringField",      0b00001000),
    PRIMARY_KEY("PrimaryKeyField", 0b00010000),
    LONG("LongField",          0b00100000);

    /** 类型位只占这几位，其余是 stored / tokenized。 */
    private static final byte TYPE_MASK = 0b00111100;

    private final String jsonName;
    private final byte bit;

    FieldType(String jsonName, int bit) {
        this.jsonName = jsonName;
        this.bit = (byte) bit;
    }

    // ------------------------------------------------------------------ 标识

    /** fdm 里的类型位（不含 stored / tokenized）。 */
    public byte bit() {
        return bit;
    }

    /** HTTP 请求体里 {@code "type"} 字段的取值。 */
    public String jsonName() {
        return jsonName;
    }

    /** 对应的 {@link Field} 实现类 —— 反序列化时用。 */
    public Class<? extends Field> fieldClass() {
        return switch (this) {
            case STRING -> StringField.class;
            case NUMBER -> NumberField.class;
            case PRIMARY_KEY -> PrimaryKeyField.class;
            case LONG -> LongField.class;
        };
    }

    // ------------------------------------------------------------------ 查

    /** 由字段实例反查类型。 */
    public static FieldType of(Field field) {
        for (FieldType t : values()) {
            if (t.fieldClass().isInstance(field)) {
                return t;
            }
        }
        throw new IllegalArgumentException("认不出的字段类型: " + field.getClass().getName());
    }

    /** 由 fdm 的类型字节反查类型。 */
    public static FieldType byBit(byte typeByte) {
        byte masked = (byte) (typeByte & TYPE_MASK);
        for (FieldType t : values()) {
            if (t.bit == masked) {
                return t;
            }
        }
        throw new IllegalArgumentException(
                "认不出的字段类型位: " + Integer.toBinaryString(typeByte & 0xFF));
    }

    /** 由 HTTP 请求体里的 {@code "type"} 反查；不在白名单里返回 {@code null}。 */
    public static FieldType byJsonName(String name) {
        for (FieldType t : values()) {
            if (t.jsonName.equals(name)) {
                return t;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 值

    /**
     * 把一个**数据源给的原始值**装成该类型的字段 —— 用于 {@code IndexService} 把
     * {@code OriDocument} 的行转成 {@code Document}。
     *
     * <p>数字或能解析成数字的字符串都接受：CSV 这类数据源可能一切都给字符串。
     * 转不成数字时抛 {@link NumberFormatException}，由调用方决定"跳过该字段"还是整条失败。
     */
    public Field fromRawValue(String name, Object raw, Field.Tokenized isTokenized, Field.Stored isStored) {
        return switch (this) {
            case STRING -> new StringField(name, String.valueOf(raw), isTokenized, isStored);
            case NUMBER -> new NumberField(name, asDouble(raw), isTokenized, isStored);
            case PRIMARY_KEY -> new PrimaryKeyField(name, asLong(raw), isTokenized, isStored);
            case LONG -> new LongField(name, asLong(raw), isTokenized, isStored);
        };
    }

    /**
     * 从**存储的字节**解回字段 —— 用于取回文档原文时重建 {@code Document}。
     *
     * <p>各类型的字节布局不同：字符串是 UTF-8 字节；数字是 8 字节 long 位模式
     * （double 先转 longBits，long 原样）。
     */
    public Field decodeStored(String name, byte[] value, Field.Tokenized isTokenized, Field.Stored isStored) {
        return switch (this) {
            case STRING -> new StringField(name, new String(value, StandardCharsets.UTF_8),
                    isTokenized, isStored);
            case NUMBER -> new NumberField(name,
                    Double.longBitsToDouble(DataInput.readLong(value)), isTokenized, isStored);
            case PRIMARY_KEY -> new PrimaryKeyField(name, DataInput.readLong(value),
                    isTokenized, isStored);
            case LONG -> new LongField(name, DataInput.readLong(value), isTokenized, isStored);
        };
    }

    /**
     * 该字段在 fdm 里记的"字段长度"。
     *
     * <p>字符串记的是**字符数**（BM25 的字段长度归一化用它），数值一律记 1 个"词"。
     * 注意这不是存储字节数 —— 存储长度由 {@code Field#customSerialize} 自己决定。
     */
    public int fieldLengthOf(Field field) {
        return switch (this) {
            case STRING -> ((StringField) field).getValue().length();
            case NUMBER, PRIMARY_KEY, LONG -> 1;
        };
    }

    // ------------------------------------------------------------------ 索引

    /** 是不是 BKD 数值点类型（只有它们进 BKD，也只有它们能做范围查询）。 */
    public boolean isNumericPoint() {
        return this == NUMBER || this == LONG;
    }

    /**
     * 编成 BKD 里存的**保序 long**。
     *
     * <p>两种数值类型用**不同的编码**（double 要翻位序、long 本身就是序），
     * 所以它们不能混用 —— 拿一种的区间去比另一种存的值毫无意义。
     *
     * @throws IllegalStateException 非数值类型
     */
    public long encodePoint(Field field) {
        return switch (this) {
            case NUMBER -> NumberUtil.double2SortableLong(((NumberField) field).getValue());
            case LONG -> NumberUtil.long2SortableLong(((LongField) field).getValue());
            default -> throw new IllegalStateException(this + " 不是数值字段，不能编成 BKD 点");
        };
    }

    // ------------------------------------------------------------------ 内部

    /** 数字或能解析成数字的字符串都接受。 */
    private static double asDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value).trim());
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value).trim());
    }
}
