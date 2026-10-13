package hawk.recall.request;

import field.LongField;
import hawk.recall.reader.DirectoryReader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 索引里**有哪些字段、分别是什么类型**。
 *
 * <h2>为什么需要它</h2>
 * 字段名来自用户输入，不可控。旧实现里同一个错误（传了不存在的字段）有**两种**表现：
 * <ul>
 *   <li>文本侧 —— {@code searchTerm} 直接 {@code NullPointerException}（崩）</li>
 *   <li>数值侧 —— {@code searchNumericRange} 返回空数组（静默 0 条）</li>
 * </ul>
 * 一个崩、一个不吭声，都是坏行为。有了 schema，编译期就能把"用户写错了字段名"
 * 和"索引真的没数据"区分开，并且**报错时能把可用字段列出来**。
 *
 * <h2>为什么 double 与 long 分开</h2>
 * 两种数值字段的**落盘编码不同**（double 走 {@code double2SortableLong}、
 * long 走 {@code long2SortableLong}），拿一种的区间去查另一种的字段，
 * 比较的是两套不同编码的 long —— 结果是垃圾且不报错。所以类型必须在 schema 里区分，
 * 让 {@code RequestCompiler} 在编译期就拦下来。
 *
 * @param textFields   可作文本检索的字段（倒排字段）
 * @param doubleFields 可作 double 范围检索的字段（{@code NumberField} + BKD）
 * @param longFields   可作 long 范围检索的字段（{@code LongField} + BKD）
 */
public record Schema(Set<String> textFields, Set<String> doubleFields, Set<String> longFields) {

    /** fdm 里 "NumberField" 的类型位。 */
    private static final byte DOUBLE_BIT = 0b00000100;
    /** fdm 里 "StringField" 的类型位。 */
    private static final byte STRING_BIT = 0b00001000;
    /** fdm 里 "Tokenized.YES" 的类型位 —— 只有置了它才真的建了倒排。 */
    private static final byte TOKENIZED_BIT = 0b00000010;

    public Schema {
        textFields = Set.copyOf(textFields);
        doubleFields = Set.copyOf(doubleFields);
        longFields = Set.copyOf(longFields);
    }

    /** 从索引元数据构造 —— 这是正常情况下唯一该用的入口。 */
    public static Schema from(DirectoryReader reader) {
        Set<String> bkd = reader.getBkdReaders().keySet();   // 只有它里面有的字段才真能做范围查询
        Set<String> text = new HashSet<>();
        Set<String> dbl = new HashSet<>();
        Set<String> lng = new HashSet<>();

        for (String field : reader.getFDMMap().keySet()) {
            // fdm 的 value 是 Pair<byte[], Float>：Left[0] 是类型位图，Right 是平均字段长度
            byte type = reader.getFDMMap().get(field).getLeft()[0];
            // 必须同时是 StringField **且** Tokenized.YES：只存原文的字符串字段
            // （如 goods 索引里的 descript）没有倒排，查它只会静默返回空。
            if ((type & STRING_BIT) != 0 && (type & TOKENIZED_BIT) != 0) {
                text.add(field);
            }
            if ((type & DOUBLE_BIT) != 0 && bkd.contains(field)) {
                dbl.add(field);
            }
            if ((type & LongField.TYPE_BIT) != 0 && bkd.contains(field)) {
                lng.add(field);
            }
        }
        return new Schema(text, dbl, lng);
    }

    public boolean hasTextField(String field) {
        return textFields.contains(field);
    }

    public boolean hasDoubleField(String field) {
        return doubleFields.contains(field);
    }

    public boolean hasLongField(String field) {
        return longFields.contains(field);
    }

    /** 报错时用的可读列表。 */
    public String describe() {
        return "文本字段=" + sorted(textFields)
                + "，double 字段=" + sorted(doubleFields)
                + "，long 字段=" + sorted(longFields);
    }

    private static List<String> sorted(Set<String> s) {
        List<String> list = new ArrayList<>(s);
        Collections.sort(list);
        return list;
    }
}
