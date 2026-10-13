package hawk.recall.request;

import hawk.recall.reader.DirectoryReader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * @param textFields    可作文本检索的字段（索引里的倒排字段）
 * @param numericFields 可作数值检索的字段（索引里有 BKD 的字段）
 */
public record Schema(Set<String> textFields, Set<String> numericFields) {

    public Schema {
        textFields = Set.copyOf(textFields);
        numericFields = Set.copyOf(numericFields);
    }

    /** 从索引元数据构造 —— 这是正常情况下唯一该用的入口。 */
    public static Schema from(DirectoryReader reader) {
        return new Schema(reader.getFDMMap().keySet(), reader.getBkdReaders().keySet());
    }

    public boolean hasTextField(String field) {
        return textFields.contains(field);
    }

    public boolean hasNumericField(String field) {
        return numericFields.contains(field);
    }

    /** 报错时用的可读列表。 */
    public String describe() {
        List<String> text = new ArrayList<>(textFields);
        List<String> numeric = new ArrayList<>(numericFields);
        Collections.sort(text);
        Collections.sort(numeric);
        return "文本字段=" + text + "，数值字段=" + numeric;
    }
}
