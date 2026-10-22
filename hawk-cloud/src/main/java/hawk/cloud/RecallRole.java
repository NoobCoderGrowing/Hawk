package hawk.cloud;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * {@code HAWK_ROLE} 是召回分片（{@code text-recall} / {@code vector-recall}）时，
 * 只装配 {@code hawk.cloud.recall} 这一包。
 *
 * <p>这一包里有 {@code POST /search} 与 {@code GET /health} —— 与 coordinator / indexer 的
 * 同名映射共存会启动失败，所以**只能**在角色匹配时装进来。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${hawk.cloud.role}'.endsWith('-recall')")
@ComponentScan("hawk.cloud.recall")
public class RecallRole {
}
