package clouddemo;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.serializer.SerializerFeature;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 调用协调器发起一轮索引构建：{@code POST /index/build}。
 *
 * <p>默认连本机 {@code http://127.0.0.1:8090}，建 3 个分片的倒排索引。
 * 想建向量索引，把 {@code role} 改成 {@code vector-indexer}，并加上
 * {@code "vectorFields": ["title"], "device": "auto"}。
 *
 * <p>202 是「受理」，不是「建好了」—— 构建是分钟级的，完成与否看协调器的轮次收敛状态。
 */
public final class CreateIndexDemo {

    private CreateIndexDemo() {
    }

    public static void main(String[] args) throws Exception {
        String url = "http://127.0.0.1:8090/index/build";

        JSONObject body = JSON.parseObject("""
                {
                  "role": "text-indexer",
                  "shardCount": 3,
                  "fieldList": [
                    {"type": "StringField",     "name": "title",    "isIndexed": "YES", "isStored": "YES"},
                    {"type": "NumberField",     "name": "price",    "isIndexed": "YES", "isStored": "YES"},
                    {"type": "StringField",     "name": "descript", "isIndexed": "NO",  "isStored": "YES"},
                    {"type": "NumberField",     "name": "digt",     "isIndexed": "NO",  "isStored": "YES"},
                    {"type": "PrimaryKeyField", "name": "uniqueID", "isIndexed": "NO",  "isStored": "YES"}
                  ]
                }
                """, Feature.OrderedField);

        System.out.println("POST " + url);
        System.out.println(JSON.toJSONString(body, SerializerFeature.PrettyFormat));

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(
                        JSON.toJSONString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        System.out.println("HTTP " + response.statusCode());
        System.out.println(response.body());
        if (response.statusCode() == 202) {
            System.out.println("已受理；构建完成与否看协调器的轮次收敛状态。");
        }
    }
}
