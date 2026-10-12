package hawk.web;

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
 * <p>与原来 Jackson 行为的两处对应关系：
 * <ul>
 *   <li>fastjson 默认**不输出值为 null 的字段** —— 与 {@code spring.jackson.default-property-inclusion: non_null}
 *       一致，所以 {@code hawk-web.yml} 里那段 Jackson 配置已随之删掉，前端不用动。</li>
 *   <li>响应体 {@link SearchService.SearchResponse} / {@link SearchService.Result} 原先用 record，
 *       而 fastjson 1.2.x **不支持 record**（会静默序列化成 {@code {}}），所以已改成带 getter 的普通类。</li>
 * </ul>
 *
 * <p>其它几点 fastjson 的坑（本项目别处已踩过，一并记在这里）：不读 Jackson 注解；对接口类型
 * 只回 JDK 代理（多态要自己按 {@code type} 分发）；构造器实例化要求参数齐全（需要无参构造器）；
 * 认不出的枚举值静默置 null。
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
