package hawk.cloud.indexer.webConfig;

import com.alibaba.fastjson.support.config.FastJsonConfig;
import com.alibaba.fastjson.support.spring.FastJsonHttpMessageConverter;

import hawk.cloud.indexer.TextIndexController;

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
 * <p>两点说明：
 * <ul>
 *   <li>fastjson 默认**不输出值为 null 的字段**，与原来 {@code spring.jackson.default-property-inclusion: non_null}
 *       的效果一致，所以前端那边不用动。</li>
 *   <li>fastjson **不读 Jackson 注解**，所以 {@code field} 包上的 {@code @JsonSubTypes} 白名单在 HTTP
 *       这一层不再起作用 —— 多态分发改成在 {@link TextIndexController} 里按 {@code type} 显式 switch。</li>
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
