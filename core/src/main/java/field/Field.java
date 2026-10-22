package field;

/**
 * 一个字段的定义 / 取值，{@code StringField} / {@code NumberField} / {@code PrimaryKeyField} 三个实现。
 *
 * <h2>多态由调用方显式分发，不依赖 JSON 框架</h2>
 * 这里**没有也不该有 JSON 注解**。项目里 JSON 用 fastjson，而 fastjson 对接口类型只会回一个
 * JDK 代理（不是具体子类），它的 autotype（{@code @type} 全类名）又是典型的反序列化 gadget 面。
 * 所以多态不用框架做，由调用方按 {@code type} 显式 switch，白名单就在那段 switch 里
 * （目前是 {@code hawk-cloud} 的 {@code IndexApi#parseFieldList}）。<b>加新的 Field 实现时要同步改那边。</b>
 *
 * <p>曾经这里挂着一套 Jackson 注解（{@code @JsonTypeInfo} / {@code @JsonSubTypes}），
 * 由 {@code Id.CLASS} 起家、后来改成 {@code Id.NAME} 白名单。HTTP 层换成 fastjson 后它们全部失效，
 * 故已删除 —— 留着会让后来人以为多态是框架在管。
 *
 * <h2>字段类型用具体枚举</h2>
 * {@code isIndexed} / {@code isStored} 直接是 {@link Indexed} / {@link Stored}，
 * <b>不要写成 {@code Enum<Indexed>}</b>：那样会被类型擦除成 {@code java.lang.Enum}，
 * 任何 JSON 框架都拿不到具体类型、反序列化必然失败。
 *
 * <h2>三个实现都有无参构造器</h2>
 * fastjson 走「无参构造 + setter」实例化。只有带参构造器时它会去调那个构造器，
 * 参数缺一个就报 {@code create instance error} —— 而「字段定义」式的请求体本来就不带 {@code value}。
 */
public interface Field {

    enum Stored {
        YES("Yes"),

        NO("No");
        Stored(String label) {
        }
    }

    enum Indexed {
        YES("Yes"),

        NO("No");
        Indexed(String label) {
        }
    }

    Indexed isIndexed();

    Stored isStored();

    byte[] customSerialize();

    byte[] serializeName();

    String getName();

}
