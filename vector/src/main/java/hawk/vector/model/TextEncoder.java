package hawk.vector.model;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.Closeable;
import java.io.IOException;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import hawk.vector.model.ModelDescriptor.Role;

/**
 * 文本编码器：文本 → L2 归一化的向量。
 *
 * <p>完全由 {@link ModelDescriptor} 驱动，对具体模型**零假设**：
 * <ul>
 *   <li>维度从描述符读，不写死</li>
 *   <li>输入名从描述符读 —— 只喂图实际具有的输入，不塞假张量</li>
 *   <li>分词交给 DJL 的 {@link HuggingFaceTokenizer}，直接读 tokenizer.json，
 *       原生支持 WordPiece / BPE / Unigram 三种算法</li>
 *   <li>前缀从描述符读，支持非对称模型</li>
 * </ul>
 *
 * <p>pooling 和 L2 归一化已经烘在 ONNX 图里，这里不做任何后处理 ——
 * 否则每支持一个新模型都要在 Java 里加一段逻辑，"任意模型"就无从谈起。
 *
 * <p>本类线程安全：{@link OrtSession#run} 与 tokenizer 都加锁串行化。
 */
public class TextEncoder implements Closeable {

    private final ModelDescriptor descriptor;
    private final OrtEnvironment env;
    private final OrtSession session;
    /**
     * 每个角色一个 tokenizer 实例，各自带着该角色的 maxLength。
     *
     * <p><b>截断和 padding 交给 tokenizer 自己做，Java 侧不手写。</b>
     * 一开始我在 Java 里用 {@code min(len, maxLen)} 做头部截断，结果把结尾的
     * {@code [SEP]} 切掉了，和 HF 的 {@code truncation=True} 行为不一致 ——
     * 超长文本的余弦只有 0.9726（其余 9 条都是 1.0000000）。
     *
     * <p>配置成 {@code LONGEST_FIRST} + {@code MAX_LENGTH} 后，两侧走的是
     * 同一个 Rust tokenizers 库的同一套语义，结构上就不可能再漂移。
     */
    private final Map<ModelDescriptor.Role, HuggingFaceTokenizer> tokenizers;
    private final ReentrantLock lock = new ReentrantLock();

    public TextEncoder(Path modelDir) throws IOException, OrtException {
        this.descriptor = ModelDescriptor.load(modelDir);

        Path onnxPath = descriptor.resolveFile(modelDir, descriptor.onnx.file);
        if (!Files.exists(onnxPath)) {
            throw new IOException("找不到 ONNX 模型: " + onnxPath);
        }

        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        this.session = env.createSession(onnxPath.toString(), opts);

        this.tokenizers = new EnumMap<>(ModelDescriptor.Role.class);
        for (ModelDescriptor.Role role : ModelDescriptor.Role.values()) {
            Map<String, String> tkOpts = new HashMap<>();
            tkOpts.put("truncation", "LONGEST_FIRST");
            tkOpts.put("padding", "MAX_LENGTH");
            tkOpts.put("maxLength", String.valueOf(descriptor.preprocess.maxLen(role)));
            tokenizers.put(role, HuggingFaceTokenizer.newInstance(modelDir, tkOpts));
        }

        verifyContract();
    }

    /**
     * 校验 ONNX 图的实际输入输出与描述符声明一致。
     *
     * <p>描述符和模型文件是分开发布、分开更新的，对不上时必须**启动即失败** ——
     * 否则会一路跑到检索结果变垃圾才被发现。
     */
    private void verifyContract() throws OrtException, IOException {
        var info = session.getInputInfo();
        for (String name : descriptor.onnx.inputNames) {
            if (!info.containsKey(name)) {
                throw new IOException(
                        "描述符声明了输入 '" + name + "'，但 ONNX 图里没有。图实际输入: " + info.keySet());
            }
        }
        if (!session.getOutputInfo().containsKey(descriptor.onnx.outputName)) {
            throw new IOException(
                    "描述符声明的输出 '" + descriptor.onnx.outputName + "' 不在图中。图实际输出: "
                            + session.getOutputInfo().keySet());
        }
    }

    public ModelDescriptor descriptor() {
        return descriptor;
    }

    public int dim() {
        return descriptor.dim;
    }

    /**
     * 批量编码。**内部按 {@code preprocess.encodeBatchSize} 分块**，调用方给多少条都行。
     *
     * <p><b>为什么必须分块</b>：ONNX Runtime 的中间激活值随 batch 规模线性增长，
     * 实测约 <b>47 KB/token</b>。一次编码 2000 条、maxLen=64 就是 12.8 万 token，
     * 峰值 RSS 冲到 6 GB —— 而 JVM 堆上限只有 1.92 GB，多出来的全在 ORT 的原生内存里，
     * {@code -Xmx} 根本管不住，最后被系统 OOM killer 杀掉。
     *
     * <p>分块后峰值内存与输入总量**解耦**，只由块大小决定：
     * <pre>
     *   不分块  峰值 ∝ 输入总量 × 长度        （2000 条 → 6 GB）
     *   分块    峰值 ∝ 块大小   × 长度        （2000 条和 200 万条，峰值相同）
     * </pre>
     *
     * <p>结果不受影响：BERT 逐样本独立（batch 内样本间无交互，
     * 且用的是逐样本的 LayerNorm），第 i 条文本落在哪一块，出来的向量都一样。
     *
     * @param texts 原始文本（不含前缀，前缀由本方法按 role 自动加）
     * @param role  角色；非对称模型据此选前缀和长度上限
     * @return (N, dim) 的 L2 归一化向量
     */
    public float[][] encode(List<String> texts, ModelDescriptor.Role role)
            throws OrtException {
        if (texts.isEmpty()) {
            return new float[0][];
        }
        int chunkSize = Math.max(1, descriptor.preprocess.encodeBatchSize);
        float[][] out = new float[texts.size()][];
        for (int start = 0; start < texts.size(); start += chunkSize) {
            int end = Math.min(start + chunkSize, texts.size());
            float[][] part = encodeChunk(texts.subList(start, end), role);
            System.arraycopy(part, 0, out, start, part.length);
        }
        return out;
    }

    /** 单块编码。调用方不要直接用 —— 走 {@link #encode} 让它负责分块。 */
    private float[][] encodeChunk(List<String> texts, ModelDescriptor.Role role)
            throws OrtException {
        int maxLen = descriptor.preprocess.maxLen(role);
        String prefix = descriptor.preprocess.prefix(role);

        List<String> prepared = new ArrayList<>(texts.size());
        for (String t : texts) {
            prepared.add(prefix + (t == null ? "" : t));
        }

        lock.lock();
        try {
            // tokenizer 已配置 padding=MAX_LENGTH + truncation=LONGEST_FIRST，
            // 返回的每条 Encoding 长度都恰好是 maxLen，且特殊 token 按 HF 的规则保留。
            Encoding[] encodings = tokenizers.get(role).batchEncode(prepared, true, false);
            int batch = encodings.length;
            long[] ids = new long[batch * maxLen];
            long[] mask = new long[batch * maxLen];
            long[] types = new long[batch * maxLen];

            for (int i = 0; i < batch; i++) {
                long[] srcIds = encodings[i].getIds();
                long[] srcMask = encodings[i].getAttentionMask();
                long[] srcTypes = encodings[i].getTypeIds();
                if (srcIds.length != maxLen) {
                    throw new IllegalStateException(
                            "tokenizer 返回长度 " + srcIds.length + "，期望 " + maxLen
                                    + "。检查 tokenizer 的 padding/truncation 配置是否生效。");
                }
                int base = i * maxLen;
                System.arraycopy(srcIds, 0, ids, base, maxLen);
                System.arraycopy(srcMask, 0, mask, base, maxLen);
                if (srcTypes != null) {
                    System.arraycopy(srcTypes, 0, types, base, Math.min(srcTypes.length, maxLen));
                }
            }

            long[] shape = {batch, maxLen};
            Map<String, OnnxTensor> inputs = new HashMap<>();
            try (OnnxTensor idsT = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape);
                 OnnxTensor maskT = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape);
                 OnnxTensor typesT = OnnxTensor.createTensor(env, LongBuffer.wrap(types), shape)) {

                // 只喂图实际具有的输入 —— 不为了"统一"造空壳张量
                for (String name : descriptor.onnx.inputNames) {
                    switch (name) {
                        case "input_ids" -> inputs.put(name, idsT);
                        case "attention_mask" -> inputs.put(name, maskT);
                        case "token_type_ids" -> inputs.put(name, typesT);
                        default -> throw new IllegalArgumentException(
                                "描述符声明了未知输入 '" + name + "'，Java 侧不知道怎么构造。"
                                        + "支持的是 input_ids / attention_mask / token_type_ids。");
                    }
                }

                try (OrtSession.Result result = session.run(inputs)) {
                    Object value = result.get(0).getValue();
                    if (value instanceof float[][] v) {
                        return v;
                    }
                    if (value instanceof float[] v) {   // batch=1 时可能退化成一维
                        return new float[][]{v};
                    }
                    throw new IllegalStateException(
                            "输出 '" + descriptor.onnx.outputName + "' 的类型是 "
                                    + (value == null ? "null" : value.getClass().getName())
                                    + "，期望 float[][]");
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public float[] encodeOne(String text, ModelDescriptor.Role role) throws OrtException {
        return encode(List.of(text), role)[0];
    }

    public ModelDescriptor.Role role(String s) {
        return "doc".equalsIgnoreCase(s) ? ModelDescriptor.Role.DOC : ModelDescriptor.Role.QUERY;
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException ignored) {
            // 关闭失败不阻塞退出
        }
        for (HuggingFaceTokenizer tk : tokenizers.values()) {
            tk.close();
        }
    }

    public static void main(String[] args) {
        try {
            TextEncoder encoder = new TextEncoder(Path.of("your model path"));
            float[] vector = encoder.encodeOne("hello", Role.QUERY);
            System.out.println(vector.length);
        } catch (Exception e) {
            System.out.println("model loading failed");
            System.exit(1);
        }
        
    }
}
