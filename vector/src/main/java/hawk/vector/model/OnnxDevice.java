package hawk.vector.model;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ONNX 推理的执行设备：**默认 GPU，没有就 CPU**。
 *
 * <h2>为什么不能只看 {@code OrtEnvironment.getAvailableProviders()}</h2>
 * 那个 API 返回的是"**制品里编译进了什么**"，不是"**这台机器能不能跑**"。
 * GPU 版制品在没装 CUDA 运行时的机器上照样会把 CUDA 列进去。所以唯一可靠的探测是
 * 真的试着注册一次 CUDA 执行提供者，接异常。
 *
 * <h2>两种失败路径，同一个异常</h2>
 * <ul>
 *   <li>CPU 版制品（默认）：{@code extractCUDA()} 返回 false → 直接抛 {@code OrtException}</li>
 *   <li>GPU 版制品但宿主机缺 CUDA 运行时的库 → 原生注册时 dlopen 失败 → 也抛 {@code OrtException}</li>
 * </ul>
 * 所以「试一次、接异常」对两种制品都成立。
 *
 * <h2>为什么不直接在用真 opts 上试</h2>
 * {@code addCUDA} 失败时可能已经在原生侧留下半注册状态。所以探测用一份**临时的**
 * {@code SessionOptions}，探测成功才把 CUDA 加到真正的 opts 上。
 *
 * <p><b>不要</b>把 {@code OrtEnvironment.getEnvironment()} 放进 try-with-resources ——
 * 它是进程级单例，close 掉会污染后续所有 session。
 */
public final class OnnxDevice {

    private static final Logger log = LoggerFactory.getLogger(OnnxDevice.class);

    private static volatile boolean nativeLibsLoaded = false;

    private OnnxDevice() {
    }

    /** 设备选择。{@link #AUTO} 是"默认 GPU，没有就 CPU"。 */
    public enum Kind {
        /** 默认：能用 GPU 就用，不能就回退 CPU，只记日志。 */
        AUTO,
        /** 必须用 GPU；不可用即失败 —— 显式要 GPU 却被静默降级是最难查的错。 */
        GPU,
        /** 只用 CPU，不做任何探测（省掉一次注定失败的尝试与噪音日志）。 */
        CPU
    }

    /**
     * 探测结果。
     *
     * @param requested  调用方要的设备
     * @param cudaActive 实际是否跑在 CUDA 上。**只有它为 true 才是真上了 GPU** ——
     *                   ONNX Runtime 存在静默降级，日志与指标都该以此为准
     * @param detail     给日志用的一句话说明
     */
    public record Resolved(Kind requested, boolean cudaActive, String detail) {
        public boolean onGpu() {
            return cudaActive;
        }
    }

    /**
     * 按 {@code kind} 配置 {@code opts}。返回值说明**实际**落在哪个设备上。
     *
     * @param opts     待配置的 session options（会被就地修改）
     * @param kind     设备选择
     * @param gpuDevice CUDA 设备号，一般 0
     * @param cudaLibDir 可选：CUDA 运行时库所在目录，非 null 时先预加载（见 {@link #preloadNativeLibs}）
     * @throws OrtException {@code kind == GPU} 且 CUDA 不可用时
     */
    public static Resolved configure(OrtSession.SessionOptions opts, Kind kind,
                                     int gpuDevice, Path cudaLibDir) throws OrtException {
        if (cudaLibDir != null) {
            preloadNativeLibs(cudaLibDir);
        }
        if (kind == Kind.CPU) {
            return new Resolved(Kind.CPU, false, "CPU");
        }

        String reason = probeCuda(gpuDevice);
        if (reason == null) {
            try {
                opts.addCUDA(gpuDevice);
                log.info("编码器使用 GPU（CUDA device {}）", gpuDevice);
                return new Resolved(kind, true, "CUDA device " + gpuDevice);
            } catch (OrtException e) {
                reason = concise(e);
            }
        }

        String detail = "CPU（CUDA 不可用：" + reason + "）";
        if (kind == Kind.GPU) {
            // 显式要 GPU 却给不了，就必须炸 —— 静默降级会让整条链路慢得莫名其妙却查不出来
            throw new OrtException(OrtException.OrtErrorCode.ORT_EP_FAIL,
                    "配置要求 GPU，但 CUDA 不可用：" + reason
                            + "。检查是否用 -Pgpu 构建、宿主机是否装了 CUDA 12 运行时"
                            + "（libcudart.so.12 / libcublas.so.12 / libcublasLt.so.12 / libcurand.so.10）。");
        }
        log.warn("GPU 不可用，回退 CPU：{}", reason);
        return new Resolved(Kind.AUTO, false, detail);
    }

    /**
     * 试一次 CUDA 注册。
     *
     * @return {@code null} 表示可用；否则返回不可用的原因
     */
    private static String probeCuda(int gpuDevice) {
        try (OrtSession.SessionOptions probe = new OrtSession.SessionOptions()) {
            probe.addCUDA(gpuDevice);
            return null;
        } catch (Exception e) {
            return concise(e);
        }
    }

    /**
     * 把 ONNX Runtime 的异常消息削成一句人话。
     *
     * <p>原始消息形如 {@code "Error code - ORT_EP_FAIL - message: Failed to find CUDA shared provider"}，
     * 整条透出来会把日志和 {@code Resolved.detail} 撑得没法读。
     */
    private static String concise(Throwable e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return e.getClass().getSimpleName();
        }
        int i = msg.indexOf("message: ");
        return i >= 0 ? msg.substring(i + "message: ".length()).trim() : msg;
    }

    /**
     * 预加载 CUDA 运行时库，**免设 {@code LD_LIBRARY_PATH}**。
     *
     * <p>GPU 版制品不打包 CUDA 运行时（见 {@code vector/pom.xml} 的 gpu profile），
     * 若不便在宿主机安装，可以把库集中到一个目录（子目录结构不限，会递归一层），
     * 由这里 {@code System.load} 进来。
     *
     * <p>两个要点：
     * <ul>
     *   <li><b>cudart 排第一</b>：它是基础运行时，别的库依赖它</li>
     *   <li><b>两遍加载</b>：库之间有依赖顺序，第一遍失败的第二遍可能就成功了</li>
     * </ul>
     * 只调用一次，后续调用是空操作。加载失败不抛异常 —— 让随后的探测去决定是否回退 CPU。
     */
    public static synchronized void preloadNativeLibs(Path dir) {
        if (nativeLibsLoaded) {
            return;
        }
        nativeLibsLoaded = true;
        if (dir == null || !Files.isDirectory(dir)) {
            log.warn("CUDA 库目录不存在，跳过预加载：{}", dir);
            return;
        }

        List<Path> libs = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry)) {
                    try (DirectoryStream<Path> files = Files.newDirectoryStream(entry, "*.so*")) {
                        files.forEach(libs::add);
                    }
                } else if (entry.getFileName().toString().contains(".so")) {
                    libs.add(entry);
                }
            }
        } catch (IOException e) {
            log.warn("扫描 CUDA 库目录失败：{}", e.getMessage());
            return;
        }
        if (libs.isEmpty()) {
            log.warn("CUDA 库目录里没有 .so 文件：{}", dir);
            return;
        }
        // cudart 优先，其余按文件名稳定排序
        libs.sort((a, b) -> {
            boolean ra = a.getFileName().toString().startsWith("libcudart");
            boolean rb = b.getFileName().toString().startsWith("libcudart");
            if (ra != rb) {
                return ra ? -1 : 1;
            }
            return a.getFileName().toString().compareTo(b.getFileName().toString());
        });

        for (int pass = 0; pass < 2; pass++) {
            for (Path lib : libs) {
                try {
                    System.load(lib.toAbsolutePath().toString());
                } catch (UnsatisfiedLinkError e) {
                    // 第一遍多半是依赖还没到；第二遍仍失败就认了
                }
            }
        }
        log.info("已预加载 {} 个 CUDA 库：{}", libs.size(), dir);
    }
}
