package hawk.cloud;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * {@code HAWK_ROLE} 是索引器（{@code text-indexer} / {@code vector-indexer}）时，
 * 只装配 {@code hawk.cloud.indexer} 这一包。
 *
 * <p>用 {@code endsWith} 而不是枚举两个值：角色词表在 `roleType.md` 里，将来加第三种索引器时
 * 这里不用改。判据只有一处 —— {@code hawk.cloud.role}，它在 yml 里无默认值，没设就直接启动失败。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${hawk.cloud.role}'.endsWith('-indexer')")
@ComponentScan("hawk.cloud.indexer")
public class IndexerRole {
}
