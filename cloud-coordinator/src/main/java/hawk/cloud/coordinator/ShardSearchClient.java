package hawk.cloud.coordinator;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把一次检索扇给**一个 role** 的所有分片，每个分片挑一个副本。
 * 方案见 {@code CLOUD_RECALL_SEARCH_DESIGN.md} §2 / §7。
 *
 * <h2>职责边界</h2>
 * 本类只做"把请求发出去、把回来的东西收齐"——**不做归并、不做去重、不懂查询语法**。
 * 归并在 {@link SearchMerger}，语法是分片的事。
 *
 * <h2>挑副本：轮询</h2>
 * 同一分片的副本**完全等价**（都持有整份分片索引），所以挑谁只影响负载。
 * 用轮询而不是随机：3 个副本时随机的方差很直观（6 次请求可能 4/1/1），而轮询是确定性的均匀，
 * 代价只是每个分片一个 {@code AtomicLong}。
 *
 * <h2>逐副本重试，**含 503**</h2>
 * 分片回 503 表示"这个副本服务不了当前版本"（没拉到索引 / 模型对不上 / 索引打不开）——
 * 那是**换一个副本**的信号，不是"这一片没结果"。两者绝不能混：
 * 把 503 当成空结果，就会**静默少掉 1/分片数 的语料，而返回的 top-10 看起来完全正常**。
 *
 * <h2>并行扇出，但有总预算</h2>
 * 分片之间的网络往返本来就可以重叠，串行等于把延迟加起来。但并行也要有上限：
 * 一个分片卡住不能拖住整个查询 —— 到点就用已经回来的那部分（没回来的记进 failed）。
 */
@Component
public class ShardSearchClient {

    private static final Logger log = LoggerFactory.getLogger(ShardSearchClient.class);

    /** 索引器角色 → 召回角色 → 检索端点。显式映射，别用字符串拼。 */
    public static final String ROLE_TEXT = "text-recall";
    public static final String ROLE_VECTOR = "vector-recall";

    private final NodeRegistry nodeRegistry;

    /** 单次分片请求的等待上限。一个分片自己慢，不该拖住其它分片。 */
    @Value("${hawk.cloud.coordinator.search.shard-timeout-ms:3000}")
    private long shardTimeoutMs;

    /** 整轮扇出的总预算（毫秒）。到点就用已回来的那部分。 */
    @Value("${hawk.cloud.coordinator.search.total-timeout-ms:5000}")
    private long totalTimeoutMs;

    /** 每次派发的连接超时。局域网，短一点。 */
    private static final int CONNECT_TIMEOUT_MS = 2_000;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .build();

    /** 每个 (role, shard) 一个轮询计数器 —— 副本集变化会让映射平移，那没关系（目的是摊开负载）。 */
    private final Map<String, AtomicLong> roundRobin = new ConcurrentHashMap<>();

    /** 扇出的线程池。**不能用 scheduling-1**（那个单线程池还跑着注册中心轮询与轮次检查）。 */
    private final ExecutorService fanOut = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "search-fanout");
        t.setDaemon(true);
        return t;
    });

    public ShardSearchClient(NodeRegistry nodeRegistry) {
        this.nodeRegistry = nodeRegistry;
    }

    /** 一个分片回来的结果。 */
    public record ShardHits(String shardNum, int totalHits, boolean totalExact,
                            List<Map<String, Object>> hits) {
    }

    /** 一个 role 一轮扇出的结果：哪些分片回了、哪些没回（附原因）。 */
    public record RouteResult(String recallRole, List<ShardHits> shards, Map<String, String> failed) {
    }

    /**
     * 把 {@code body} 扇给这个 role 的每一个分片。
     *
     * @param body 给分片的请求体（各 role 形状不同，由调用方按 role 构造）
     */
    public RouteResult searchRoute(String recallRole, int shardCount, JSONObject body) {
        Map<String, Map<String, String>> byShard = shardsOf(recallRole);
        String endpoint = endpointOf(recallRole);

        List<ShardHits> collected = new ArrayList<>();
        Map<String, String> failed = new LinkedHashMap<>();

        List<Future<?>> futures = new ArrayList<>(shardCount);
        for (int i = 0; i < shardCount; i++) {
            String shard = "shard-" + i;
            Map<String, String> replicas = byShard.get(shard);
            if (replicas == null || replicas.isEmpty()) {
                failed.put(shard, "没有在线副本");
                continue;
            }
            futures.add(fanOut.submit(() -> {
                ShardHits hits = searchOneShard(recallRole, shard, replicas, endpoint, body);
                synchronized (collected) {
                    if (hits != null) {
                        collected.add(hits);
                    } else {
                        failed.put(shard, "所有副本都不可用");
                    }
                }
            }));
        }

        // 总预算：到点就不等了（没回来的分片后面记 failed）
        long deadline = System.currentTimeMillis() + totalTimeoutMs;
        for (Future<?> f : futures) {
            long left = deadline - System.currentTimeMillis();
            try {
                f.get(Math.max(1, left), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                f.cancel(true);
            }
        }
        // 没等到结果的分片，如实记下来 —— 不能让"少了一片"看起来像"那一片没有命中"
        for (int i = 0; i < shardCount; i++) {
            String shard = "shard-" + i;
            if (failed.containsKey(shard)) {
                continue;
            }
            boolean answered = collected.stream().anyMatch(h -> h.shardNum().equals(shard));
            if (!answered) {
                failed.put(shard, "超时（总预算 " + totalTimeoutMs + "ms 内没回来）");
            }
        }
        return new RouteResult(recallRole, collected, failed);
    }

    @PreDestroy
    public void shutdown() {
        fanOut.shutdownNow();
    }

    // ------------------------------------------------------------------

    /** 一个分片：轮询挑一个副本，失败就换下一个（含 503）。 */
    private ShardHits searchOneShard(String recallRole, String shardNum, Map<String, String> replicas,
                                     String endpoint, JSONObject body) {
        List<Map.Entry<String, String>> candidates = new ArrayList<>(replicas.entrySet());
        candidates.sort(Map.Entry.comparingByKey());        // 稳定顺序，轮询才有意义
        int start = Math.floorMod(
                roundRobin.computeIfAbsent(recallRole + "/" + shardNum, k -> new AtomicLong())
                        .getAndIncrement(),
                candidates.size());

        String bodyText = body.toJSONString();
        for (int step = 0; step < candidates.size(); step++) {
            Map.Entry<String, String> replica = candidates.get((start + step) % candidates.size());
            ShardHits hits = post(replica.getValue(), endpoint, bodyText, shardNum);
            if (hits != null) {
                return hits;
            }
            // 503 / 连不上 / 超时 —— 都换下一个副本（不排队、不当作"这一片没结果"）
            log.debug("{} {} 的副本 {} 不可用，换下一个", recallRole, shardNum, replica.getKey());
        }
        log.warn("{} {} 的 {} 个副本都不可用", recallRole, shardNum, candidates.size());
        return null;
    }

    /** @return 分片的结果；**503 / 连不上 / 超时 / 响应畸形**一律返回 null（调用方换副本） */
    private ShardHits post(String address, String endpoint, String bodyText, String shardNum) {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://" + address + endpoint))
                    .timeout(Duration.ofMillis(shardTimeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyText, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 503) {
                // "这个副本服务不了当前版本" —— 换副本，不是"没结果"
                log.info("分片 {} 的 {} 回 503（服务不了当前版本），换副本", shardNum, address);
                return null;
            }
            if (response.statusCode() != 200) {
                log.warn("分片 {} 的 {} 返回 {}：{}", shardNum, address,
                        response.statusCode(), response.body());
                return null;
            }
            return parse(response.body(), shardNum);
        } catch (Exception e) {
            log.warn("分片 {} 的 {} 请求失败：{}", shardNum, address, e.toString());
            return null;
        }
    }

    private ShardHits parse(String body, String shardNum) {
        JSONObject json = JSON.parseObject(body);
        if (json == null) {
            return null;
        }
        JSONArray rawHits = json.getJSONArray("hits");
        List<Map<String, Object>> hits = new ArrayList<>();
        if (rawHits != null) {
            for (int i = 0; i < rawHits.size(); i++) {
                JSONObject h = rawHits.getJSONObject(i);
                if (h != null) {
                    hits.add(new LinkedHashMap<>(h));    // JSONObject 本身就是 Map<String,Object>
                }
            }
        }
        Object total = json.get("totalHits");
        int totalHits = total instanceof Number n ? n.intValue() : hits.size();
        // totalExact 缺省当真：宁可说"这个数准"，也不要把"准的数"误标成下界
        boolean exact = !Boolean.FALSE.equals(json.getBoolean("totalExact"));
        return new ShardHits(shardNum, totalHits, exact, hits);
    }

    /** role → 端点。text-recall 与 vector-recall 的形状不同，所以是两个路径。 */
    public static String endpointOf(String recallRole) {
        return switch (recallRole) {
            case ROLE_TEXT -> "/search";
            case ROLE_VECTOR -> "/vectorSearch";
            default -> throw new IllegalArgumentException("不认识的召回角色：" + recallRole);
        };
    }

    private Map<String, Map<String, String>> shardsOf(String recallRole) {
        return switch (recallRole) {
            case ROLE_TEXT -> nodeRegistry.textRecalls();
            case ROLE_VECTOR -> nodeRegistry.vectorRecalls();
            default -> throw new IllegalArgumentException("不认识的召回角色：" + recallRole);
        };
    }
}
