package hawk.cloud.coordinator;

import com.alibaba.fastjson.serializer.SerializerFeature;
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
 * <p>协调器的对外端点是 {@code POST /index/build} 与 {@code POST /indexComplete}，
 * 以及内部看的记录；它们全部走 fastjson，不会有人不小心落回 Jackson。
 *
 * <p>注意 fastjson 的几处坑（都在本项目的其它地方踩过）：
 * <ul>
 *   <li>它**不读 Jackson 注解**；</li>
 *   <li>对**接口类型**只会回一个 JDK 代理，多态必须自己按 {@code type} 分发；</li>
 *   <li>构造器实例化要求所有参数都在 JSON 里，所以目标类需要无参构造器；</li>
 *   <li>认不出的**枚举值静默置 null**；</li>
 *   <li>**不支持 record**，会序列化成 {@code {}}；</li>
 *   <li>默认开启**循环引用检测**，见下面那条 —— 这里是本模块最容易踩的一个。</li>
 * </ul>
 */
@Configuration
public class FastJsonWebConfig implements WebMvcConfigurer {

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        FastJsonHttpMessageConverter fastJson = new FastJsonHttpMessageConverter();

        FastJsonConfig config = new FastJsonConfig();
        config.setCharset(StandardCharsets.UTF_8);

        // 关掉循环引用检测。
        //
        // 它本来是防对象图成环的，但判定用的是**对象身份**，于是"两个内容相同的对象"也会被写成
        //   {"dispatched":{}, "failed":{"$ref":"$.dispatched"}}
        // —— 第二个字段的**真实内容被一个引用字符串顶掉了**，调用方读到的不是空 map 而是一句
        // "$ref"，而且**序列化端一切正常、不报任何错**。
        //
        // 这不是假想：Map.of() 空调用在 JDK 9+ 返回的是**同一个**不可变单例，
        // 所以 IndexBuildController 在"全部派发成功"和"一个都没派出去"这两种情况下
        // dispatched / failed 必然同时是空 map。实测就撞上了。
        //
        // 代价：真出了环会栈溢出而不是输出 $ref。本模块的响应都是扁平 DTO，值得这个交换。
        config.setSerializerFeatures(SerializerFeature.DisableCircularReferenceDetect);

        fastJson.setFastJsonConfig(config);
        fastJson.setSupportedMediaTypes(List.of(MediaType.APPLICATION_JSON));

        converters.add(0, fastJson);
    }
}
