package hawk.cloud.coordinator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 解析 {@code registry-address}，两种写法都认：
 * <pre>
 *   127.0.0.1:6001                                               单节点
 *   {1:'127.0.0.2:6001',2:'127.0.0.2:6002',3:'127.0.0.3:6003'}   集群节点表
 * </pre>
 * 节点表按 key 的数值升序排列，返回顺序即尝试顺序。
 *
 * <p><b>注意：这段逻辑在 cloud-indexer 与 cloud-recall 的 RegistryRegistrar 里各有一份
 * 一模一样的拷贝。</b>三处重复，改一处漏两处的风险是实在的 —— 该抽到一个共享模块里去，
 * 只是还没做（见提交说明）。
 *
 * <p>这里**不容错外层引号**：值应当直接是 {@code {1:'…'}}，再套一层 {@code "…"} 会让整体
 * 退化成「单节点」分支而解析失败。节点值本身的那对单引号是表语法的一部分，会被去掉。
 */
final class RegistryAddresses {

    private static final Logger log = LoggerFactory.getLogger(RegistryAddresses.class);

    private RegistryAddresses() {
    }

    /** @return 候选节点；一个都解析不出时返回空列表 */
    static List<InetSocketAddress> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String value = raw.trim();

        List<Map.Entry<Long, String>> entries = new ArrayList<>();
        if (value.startsWith("{") && value.endsWith("}")) {
            for (String item : value.substring(1, value.length() - 1).split(",")) {
                int colon = item.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String hostPort = stripQuotes(item.substring(colon + 1).trim());
                long order;
                try {
                    order = Long.parseLong(item.substring(0, colon).trim());
                } catch (NumberFormatException e) {
                    order = Long.MAX_VALUE;   // 非数字 key：排到最后
                }
                entries.add(Map.entry(order, hostPort));
            }
            entries.sort(Comparator.comparingLong(Map.Entry::getKey));
        } else {
            entries.add(Map.entry(0L, value));
        }

        List<InetSocketAddress> out = new ArrayList<>();
        for (Map.Entry<Long, String> entry : entries) {
            InetSocketAddress address = asSocketAddress(entry.getValue());
            if (address != null) {
                out.add(address);
            } else {
                log.warn("registry-address 里这一项不是 host:port，已跳过：{}", entry.getValue());
            }
        }
        return out;
    }

    /** 解析单个 {@code host:port}；不合法返回 null。 */
    private static InetSocketAddress asSocketAddress(String hostPort) {
        int i = hostPort.lastIndexOf(':');
        if (i <= 0 || i == hostPort.length() - 1) {
            return null;
        }
        try {
            return new InetSocketAddress(hostPort.substring(0, i), Integer.parseInt(hostPort.substring(i + 1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 去掉节点表值外面成对的那层引号（单双都认）。 */
    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }
}
