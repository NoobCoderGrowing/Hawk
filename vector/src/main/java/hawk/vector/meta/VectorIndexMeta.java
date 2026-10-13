package hawk.vector.meta;

import com.alibaba.fastjson.JSON;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code meta.json} 的映射 —— 索引目录里那份"这个索引是什么"的描述。
 *
 * <p>存在的理由：索引文件本身只存图，<b>不存模型指纹、不存字段集</b>。没有元数据，
 * 加载时就无从判断"这个索引和当前模型配不配" —— 配错了不会报错，只会一路跑出垃圾结果。
 *
 * <p>加载时的校验（见 {@code VectorSearcher}）：{@code dim} 与 {@code modelFingerprint}
 * 必须与当前 {@code TextEncoder} 一致，否则**启动即失败**。
 *
 * <h2>目录布局</h2>
 * <pre>
 * &lt;index-dir&gt;/
 * ├── meta.json          本文件
 * ├── title/             一个字段一个子目录
 * │   ├── graph.idx      HNSW 图（JVectorGraph.save 产出）
 * │   └── pk.map         复用 directory.PkMapStore：uniqueID → 该图内的 ordinal
 * └── descript/
 *     ├── graph.idx
 *     └── pk.map
 * </pre>
 */
public class VectorIndexMeta {

    /** 元数据文件名。 */
    public static final String FILE_NAME = "meta.json";

    /** 单个字段的图文件名，位于 {@code <index-dir>/<字段名>/} 下。 */
    public static final String GRAPH_FILE = "graph.idx";

    /** 模型标识（如 {@code BAAI/bge-small-zh-v1.5}）。 */
    public String modelName;

    /** 权重指纹。加载时用它判断"索引与模型是否匹配"。 */
    public String modelFingerprint;

    /** 建索引时用的模型目录。**只是提示** —— 换机部署时用显式参数覆盖。 */
    public String modelDir;

    /** 向量维度。 */
    public int dim;

    /** 主键字段名（如 {@code uniqueID}）。 */
    public String primaryKeyField;

    /** HNSW 的 M（每个节点的邻居数），仅作记录 —— 加载时用不到，重建时才需要知道当初怎么建的。 */
    public int m;

    /** HNSW 的 efConstruction，同上。 */
    public int efConstruction;

    /** 每个被向量化的字段。 */
    public List<FieldMeta> fields = new ArrayList<>();

    /** 一个人字段在索引里的样子。 */
    public static class FieldMeta {
        /** 字段名。 */
        public String name;
        /** 融合权重。 */
        public float weight;
        /** 该图里的向量条数 —— **可能小于文档总数**：缺这个字段的文档不会进这张图。 */
        public int count;

        public FieldMeta() {
        }

        public FieldMeta(String name, float weight, int count) {
            this.name = name;
            this.weight = weight;
            this.count = count;
        }
    }

    public FieldMeta field(String name) {
        for (FieldMeta f : fields) {
            if (f.name.equals(name)) {
                return f;
            }
        }
        return null;
    }

    public static void save(Path indexDir, VectorIndexMeta meta) throws IOException {
        Files.createDirectories(indexDir);
        Files.writeString(indexDir.resolve(FILE_NAME), JSON.toJSONString(meta));
    }

    public static VectorIndexMeta load(Path indexDir) throws IOException {
        Path path = indexDir.resolve(FILE_NAME);
        if (!Files.exists(path)) {
            throw new IOException("索引目录里没有 " + FILE_NAME + "：" + indexDir.toAbsolutePath()
                    + "\n  先用 VectorIndexWriter 建一个索引");
        }
        return JSON.parseObject(Files.readAllBytes(path), VectorIndexMeta.class);
    }
}
