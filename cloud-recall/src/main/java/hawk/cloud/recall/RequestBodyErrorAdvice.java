package hawk.cloud.recall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

/**
 * 把「请求体读不进来」的响应体统一成 {@code {"message": …}}。
 *
 * <h2>为什么需要它</h2>
 * {@link Spring7FastJsonHttpMessageConverter} 把解析失败正确地变成了 400，但**消息还是到不了调用方** ——
 * Spring 的 {@code DefaultHandlerExceptionResolver} 认得 {@code HttpMessageNotReadableException}，
 * 它会**自己生成一个默认错误体**：
 * <pre>
 *   {"timestamp":…,"status":400,"error":"Bad Request","path":"/search"}
 * </pre>
 * 我们构造时那句消息被丢掉了（Spring Boot 3+ 的 {@code server.error.include-message} 默认是 {@code never}）。
 *
 * <p>于是调用方只知道"400"，不知道"哪儿错了"。而本项目其它错误（controller 自己抛的 400）返回的是
 * {@code {"message": …}} —— 这个 advice 顺带把两处**统一**了。
 *
 * <h2>只处理这一个异常</h2>
 * 不写成"兜住所有异常"：那样会把真正的服务端缺陷也包装成客户端错误。
 * 这里只接管"请求体本身读不了 / 解析不了"这一类 —— 它天然就是调用方的问题。
 *
 * <p>404 / 405 之类仍然是 Spring 的默认形状（本 advice 不管）。要全部统一是另一件事。
 *
 * <h2>⚠️ 这个类是<b>三份</b>副本</h2>
 * 同 {@code Spring7FastJsonHttpMessageConverter} / {@code IndexStamp}：三个 cloud 模块各一份，
 * 因为没有共享模块。<b>改这里要同改另两份。</b>
 */
@ControllerAdvice
public class RequestBodyErrorAdvice {

    private static final Logger log = LoggerFactory.getLogger(RequestBodyErrorAdvice.class);

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> onUnreadableBody(HttpMessageNotReadableException e) {
        // 消息本身已经是给调用方看的（转换器那边刻意不把 NoSuchMethodError 拼进去），
        // 这里原样透出即可；cause 留在日志里。
        log.debug("请求体读取失败：{}", e.getMessage(), e);
        return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
    }
}
