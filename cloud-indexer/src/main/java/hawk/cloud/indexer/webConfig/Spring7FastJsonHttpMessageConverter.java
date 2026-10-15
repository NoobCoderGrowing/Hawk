package hawk.cloud.indexer.webConfig;

import com.alibaba.fastjson.support.spring.FastJsonHttpMessageConverter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.io.IOException;
import java.lang.reflect.Type;

/**
 * fastjson 1.2.83 的消息转换器，**但把「解析失败」的上报路径修好了**。
 *
 * <h2>它修的是什么</h2>
 * fastjson 1.2.83 的 {@code FastJsonHttpMessageConverter.readType} 里是这么写的：
 * <pre>
 *   } catch (JSONException ex) {
 *       throw new HttpMessageNotReadableException("JSON parse error: " + ex.getMessage(), ex);
 *   }                                  //                            ^^^^^^^^^^^^^^^^^^^^^^
 * </pre>
 * 而 <b>Spring 7 里没有 {@code (String, Throwable)} 这个构造了</b> —— 只剩
 * {@code (String, HttpInputMessage)} 和 {@code (String, Throwable, HttpInputMessage)}：
 *
 * <table border="1">
 *   <tr><th>Spring</th><th>{@code (String, Throwable)}</th></tr>
 *   <tr><td>5.3.x</td><td>有</td></tr>
 *   <tr><td><b>7.0.9（本项目）</b></td><td><b>没有</b>（Spring 6 标废弃，7 删掉）</td></tr>
 * </table>
 *
 * <p>于是「请求体解析失败」这件事会变成三连击：
 * <ol>
 *   <li>{@code NoSuchMethodError} —— 找不到那个构造器；</li>
 *   <li><b>原来那个 {@code JSONException} 就此丢失</b>（它只是那个从未调用成功的构造器的实参）；</li>
 *   <li>{@code NoSuchMethodError} 是 <b>{@code Error} 不是 {@code Exception}</b> ——
 *       Spring MVC 的异常解析（它<b>认得</b> {@code HttpMessageNotReadableException} 并会映射成 400）
 *       <b>根本看不到它</b>，于是冒到 Tomcat → <b>500</b>。</li>
 * </ol>
 *
 * <h2>为什么这个 500 值得修</h2>
 * <b>可用性</b>：客户端写错请求体，拿到的是 500 —— 监控会当服务端故障告警、调用方会重试，
 * 而该做的是**改请求**。<br>
 * <b>可诊断性</b>：错误里那句 {@code NoSuchMethodError} 看起来像 classpath / 版本冲突，
 * 会把排查的人带去查依赖，而不是回来看自己的 JSON。
 *
 * <h2>⚠️ 这里 {@code catch (Throwable)} 是<b>刻意</b>的，别改成 {@code catch (Exception)}</h2>
 * 那个 {@code NoSuchMethodError} **正是 {@code Error}** —— 收窄成 {@code Exception} 会让这个 bug
 * 原封不动地回来。整个方法体就是"读客户端的请求体"，
 * 在那里出的任何问题，落成 400 都比一个误导性的 500 强。
 *
 * <h2>⚠️ 这个类是<b>三份</b>副本</h2>
 * {@code cloud-recall}（{@code hawk.cloud.recall}）/ {@code cloud-coordinator}（{@code hawk.cloud.coordinator}）/ {@code cloud-indexer}（{@code hawk.cloud.indexer.webConfig}）各一份
 * （三个模块都是独立的 Spring Boot 应用，没有共享模块 —— 同 {@code IndexStamp} / {@code IndexRound}
 * 的处境）。<b>改这里要同改另两份。</b>
 *
 * <p>真正的解法是把 fastjson 换成 fastjson2（它的 {@code readType} 传了 {@code inputMessage}，
 * 在 Spring 7 上是对的）—— 但那是"不同 artifact、不同 API"的迁移，
 * 涉及全项目 20 来个文件，不该挂在这么一个 catch 块上顺手做。见根目录的 `pom.xml` 注释。
 */
public class Spring7FastJsonHttpMessageConverter extends FastJsonHttpMessageConverter {

    private static final Logger log = LoggerFactory.getLogger(Spring7FastJsonHttpMessageConverter.class);

    @Override
    public Object read(Type type, Class<?> contextClass, HttpInputMessage inputMessage)
            throws IOException, HttpMessageNotReadableException {
        try {
            return super.read(type, contextClass, inputMessage);
        } catch (Throwable t) {
            // 用**三参**那个构造 —— 它在 Spring 7 里还在（见类注释）。
            // 原始 throwable 作为 cause 传下去（别再丢一次），**但不要拼进消息里**：
            // 它这一层是 NoSuchMethodError，把那段文本透给调用方只会误导 ——
            // 那是我们自己的实现细节，不是调用方能处理的东西。细节进日志。
            log.warn("读取请求体失败（已转成 400）：{}", t.toString());
            throw new HttpMessageNotReadableException("请求体不是合法的 JSON", t, inputMessage);
        }
    }
}
