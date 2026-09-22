package hawk.vector.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code model.json} 的映射。
 *
 * <p>这是 Java 侧**唯一的可变配置来源** —— 代码里不允许出现任何与具体模型相关的常量。
 * 维度、输入名、长度、前缀全部由此而来，换模型就是换一个目录。
 *
 * <p>文件由 Python 侧 {@code hawk_vector.export.export_onnx} 产出。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelDescriptor {

    public String name;
    public String fingerprint;
    public int dim;
    public String pooling;
    public OnnxSpec onnx;
    public TokenizerSpec tokenizer;
    public Preprocess preprocess;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OnnxSpec {
        public String file;
        /** 图**实际**具有的输入名。BERT 系含 token_type_ids，XLM-R 系不含。 */
        public List<String> inputNames;
        public String outputName;

        /** 为 true 时该图可以接受 token_type_ids。 */
        public boolean hasInput(String name) {
            return inputNames != null && inputNames.contains(name);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TokenizerSpec {
        public String file;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Preprocess {
        public int maxLenQuery = 32;
        public int maxLenDoc = 64;
        public String queryPrefix = "";
        public String docPrefix = "";

        /**
         * 编码时每批处理多少条文本。**必须分块**，原因见 {@link TextEncoder#encode}。
         *
         * <p>留了默认值 64，所以老的 model.json（没有这个字段）也能正常加载 ——
         * 但会失去按模型调优的能力，导出时应显式写入。
         */
        public int encodeBatchSize = 64;

        /** 该角色的长度上限。**role 为 null 时按 QUERY 处理**（见 {@link Role}）。 */
        public int maxLen(Role role) {
            return role == Role.DOC ? maxLenDoc : maxLenQuery;
        }

        /** 该角色的前缀。**role 为 null 时按 QUERY 处理**（见 {@link Role}）。 */
        public String prefix(Role role) {
            String p = role == Role.DOC ? docPrefix : queryPrefix;
            return p == null ? "" : p;
        }
    }

    /**
     * 文本在检索里扮演的角色。非对称模型（如 E5）两侧的前缀与长度上限不同。
     *
     * <p><b>约定：{@code null} 等价于 {@link #QUERY}。</b>
     * 检索场景里绝大多数调用是 query，所以让 null 走 query 分支最不意外。
     * 反之若默认为 DOC，传错时会静默地用更长的截断和 doc 前缀，向量悄悄变差却不报错 ——
     * 这种错最难查。
     *
     * <p>实现上统一用 {@code role == Role.DOC ? doc : query} 的写法（而不是
     * {@code role == Role.QUERY ? query : doc}），null 自然落到 query 分支。
     */
    public enum Role {
        QUERY, DOC
    }

    public static ModelDescriptor load(Path modelDir) throws IOException {
        Path path = modelDir.resolve("model.json");
        if (!Files.exists(path)) {
            throw new IOException("找不到描述符: " + path);
        }
        return new ObjectMapper().readValue(Files.readAllBytes(path), ModelDescriptor.class);
    }

    /** 把描述符里的相对路径解析成绝对路径。 */
    public Path resolveFile(Path modelDir, String relative) {
        return modelDir.resolve(relative);
    }
}
