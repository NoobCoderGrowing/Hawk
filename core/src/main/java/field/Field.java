package field;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 一个字段的定义 / 取值，{@code StringField} / {@code NumberField} / {@code PrimaryKeyField} 三个实现。
 *
 * <h2>多态判别字段：用短名，不用全类名</h2>
 * 这里曾经是 {@code use = Id.CLASS}，也就是请求里得写 {@code "type":"field.StringField"} 这种全类名。
 * 两个问题：
 * <ul>
 *   <li>把 classpath 上任意类名交给 Jackson 实例化，对外的 HTTP 契约上是个反序列化 gadget 面；</li>
 *   <li>与实现类各自的 {@code @JsonTypeInfo} 打架（{@code StringField} 声明的是 {@code @class}，
 *       {@code NumberField} 声明的是 {@code type}），同一个字段按静态类型不同序列化出不同形态。</li>
 * </ul>
 * 现在统一成 {@code Id.NAME} + 显式白名单，请求里写 {@code "type":"StringField"}。
 * 名字只在这个 {@code @JsonSubTypes} 里出现一次，实现类上不再各自声明。
 *
 * <h2>字段类型：具体枚举，不再是 {@code Enum<...>}</h2>
 * 原先 {@code isTokenized}/{@code isStored} 声明成 {@code Enum<Field.Tokenized>}，靠
 * {@code @JsonDeserialize(as = Field.Tokenized.class)} 给 Jackson 提示。那个注解属于
 * {@code com.fasterxml.jackson.databind.annotation} 包 —— 在 Jackson 3 里它搬到了
 * {@code tools.jackson.databind.annotation}，而 Spring Boot 4 的 HTTP 层用的正是 Jackson 3，
 * 于是提示<b>被静默忽略</b>：类型擦除成 {@code java.lang.Enum}，反序列化报
 * "Cannot construct instance of java.lang.Enum"。编译期看不出来，因为 Jackson 2 的注解 jar
 * 确实还在 classpath 上。
 *
 * <p>改用具体枚举类型后不再需要任何提示，Jackson 直接按 {@code "YES"}/{@code "NO"} 解析。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = StringField.class, name = "StringField"),
        @JsonSubTypes.Type(value = NumberField.class, name = "NumberField"),
        @JsonSubTypes.Type(value = PrimaryKeyField.class, name = "PrimaryKeyField"),
})
public interface Field {

    enum Stored {
        YES("Yes"),

        NO("No");
        Stored(String label) {
        }
    }

    enum Tokenized {
        YES("Yes"),

        NO("No");
        Tokenized(String label) {
        }
    }

    Tokenized isTokenized();

    Stored isStored();

    byte[] customSerialize();

    byte[] serializeName();

    String getName();

}
