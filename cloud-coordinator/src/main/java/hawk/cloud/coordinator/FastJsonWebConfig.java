package hawk.cloud.coordinator;

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
 * <p>协调器目前没有对外端点（只有 Tomcat 起着），这个配置是为了一致性 ——
 * 一旦加接口，序列化默认就是 fastjson，不会有人不小心落回 Jackson。
 *
 * <p>注意 fastjson 的几处坑（都在本项目的其它地方踩过）：
 * <ul>
 *   <li>它**不读 Jackson 注解**；</li>
 *   <li>对**接口类型**只会回一个 JDK 代理，多态必须自己按 {@code type} 分发；</li>
 *   <li>构造器实例化要求所有参数都在 JSON 里，所以目标类需要无参构造器；</li>
 *   <li>认不出的**枚举值静默置 null**；</li>
 *   <li>**不支持 record**，会序列化成 {@code {}}。</li>
 * </ul>
 */
@Configuration
public class FastJsonWebConfig implements WebMvcConfigurer {

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        FastJsonHttpMessageConverter fastJson = new FastJsonHttpMessageConverter();

        FastJsonConfig config = new FastJsonConfig();
        config.setCharset(StandardCharsets.UTF_8);
        fastJson.setFastJsonConfig(config);
        fastJson.setSupportedMediaTypes(List.of(MediaType.APPLICATION_JSON));

        converters.add(0, fastJson);
    }
}
