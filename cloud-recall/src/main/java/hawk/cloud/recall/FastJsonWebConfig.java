package hawk.cloud.recall;

import com.alibaba.fastjson.support.config.FastJsonConfig;
import com.alibaba.fastjson.support.spring.FastJsonHttpMessageConverter;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * HTTP 层的 JSON 换成 fastjson，不用 Jackson。
 *
 * <p>做法是把 fastjson 的 {@link FastJsonHttpMessageConverter} 插到转换器列表**最前面**，
 * 压过 Boot 自动配置进来的 Jackson 转换器（请求体和响应体就都走 fastjson 了）。
 *
 * <p>fastjson 默认**不输出值为 null 的字段**，与原来 {@code spring.jackson.default-property-inclusion: non_null}
 * 的效果一致。
 *
 * <p>注意 fastjson 的几处坑（都在本项目的其它地方踩过）：
 * <ul>
 *   <li>它**不读 Jackson 注解** —— {@code field} 包上的 {@code @JsonSubTypes} 白名单在 HTTP 层不再生效；</li>
 *   <li>对**接口类型**只会回一个 JDK 代理，多态必须自己按 {@code type} 分发；</li>
 *   <li>构造器实例化要求所有参数都在 JSON 里，所以目标类需要无参构造器；</li>
 *   <li>认不出的**枚举值静默置 null**，需要的地方要自己校验；</li>
 *   <li>**不支持 record**，会序列化成 {@code {}}。</li>
 * </ul>
 */
@Configuration
public class FastJsonWebConfig implements WebMvcConfigurer {

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        // 用我们自己的子类，不是原版 —— 原版在 Spring 7 上**解析失败会变成 500**
        // （错误上报路径调了一个 7 里已经没有的构造器）。见 Spring7FastJsonHttpMessageConverter。
        FastJsonHttpMessageConverter fastJson = new Spring7FastJsonHttpMessageConverter();

        FastJsonConfig config = new FastJsonConfig();
        config.setCharset(StandardCharsets.UTF_8);
        fastJson.setFastJsonConfig(config);
        fastJson.setSupportedMediaTypes(List.of(MediaType.APPLICATION_JSON));

        converters.add(0, fastJson);
    }
}
