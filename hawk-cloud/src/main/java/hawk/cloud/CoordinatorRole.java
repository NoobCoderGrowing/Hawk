package hawk.cloud;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * {@code HAWK_ROLE=coordinator} 时，只装配 {@code hawk.cloud.coordinator} 这一包。
 *
 * <p>这里用精确相等而不是 {@code endsWith}：coordinator 是**唯一**不带后缀的角色，
 * 而且它的登记 key 只有 {@code /cloud/coordinator} 一段（不带分片），
 * 让它只能被显式的 {@code coordinator} 匹配到，别的一个都进不来。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "hawk.cloud.role", havingValue = "coordinator")
@ComponentScan("hawk.cloud.coordinator")
public class CoordinatorRole {
}
