# Hawk

Hawk 是一个**垂直搜索引擎**的检索内核实现：写入端（indexer）与召回端（recall）解耦，支持中文分词、倒排索引、BM25 打分、数值范围查询与主键级别的软删除。

召回侧有**两条并列的链路**：倒排索引（§1）与稠密向量召回（§4）。两者目前相互独立，尚未做混合召回。

> **倒排链路**：`core` / `segment` / `indexer` / `recall` / `demo` / `benchmark`
> **向量链路**：`vector`（不依赖上述任何一个）

> 索引文件格式的逐字节说明见 [`INDEX_FORMAT.md`](INDEX_FORMAT.md)。
>
> 参与开发请联系：wenjun.yao@uqconnect.edu.au

---

## 1. 项目架构

### 1.1 模块划分

Maven 多模块工程，`core` 位于依赖链底部：

```
hawk (pom)
├── core        基础层：Directory / 文件格式 / 编解码 / BKD 树 / 字段模型
├── segment     中文分词：N-最短路径分词、词图、标点与词典资源（纯 Java，不依赖任何框架）
├── indexer     写入层：IndexWriter / DocWriter / IndexMerger
├── recall      召回层：DirectoryReader / Searcher / Query / Similarity / IndexAdmin
├── demo        示例与命令行入口：BuildIndex（建索引）、检索、删除文档
└── vector      稠密向量召回：ONNX 文本编码 + HNSW 索引（详见 §4）
```

依赖关系：

```
              recall  indexer        vector
                 │    ╱   │            │
                 │  ╱     │            │  （不依赖任何其他模块）
              segment   core          ┘
```

- `indexer` 依赖 `core` + `segment`；`recall` 依赖 `core` + `segment`；**`indexer` 与 `recall` 之间无直接依赖**，二者仅通过磁盘上的索引目录通信——这正是"写入端与召回端解耦"在代码层面的体现：可以分别部署到不同进程/机器。
- `recall` 额外引入 `io.github.noobcodergrowing:JFST`，用于内存中的 Term FST。

> `benchmark` 模块仅用于性能测评，不属于运行时依赖链，见 [§3](#3-与-lucene-的基准对比)。

### 1.2 核心抽象

| 抽象 | 位置 | 职责 |
|------|------|------|
| `Directory` / `FSDirectory` / `MMapDirectory` | `core/directory` | 索引目录抽象；MMap 实现用于只读映射段文件 |
| `Document` / `Field` | `core/document`、`core/field` | 文档与字段模型：`StringField`、`DoubleField`、`PrimaryKeyField` |
| `IndexWriter` / `DocWriter` | `indexer/writer` | 多线程写入、内存倒排积累、按 RAM 水位刷盘 |
| `IndexMerger` | `indexer/writer` | 段合并，合并后只保留 `1.*` |
| `DirectoryReader` / `MMapDirectoryReader` | `recall/reader` | 打开索引、构建 Term FST、加载 fdx/fdm/pk 映射 |
| `Searcher` | `recall/search` | 执行查询、BM25 打分、Top-N 收集、取回原文 |
| `Analyzer` / `NShortestPathAnalyzer` | `segment/core` | 中文 N-最短路径分词 |

### 1.3 写入链路

```
IndexWriter.addDoc(Document)
  └─ 提交 DocWriter 任务到 ThreadPoolExecutor（线程数默认 = Constants.PROCESSOR_NUM，由物理核数推导）
        ├─ 累积 stored 字段  → fdt 内存池
        ├─ 累积倒排 (field,term) → ivt 内存表
        ├─ 累积数值点 (sortableLong, docID) → bkdFields
        └─ 登记主键 → pkMap

触发 flush（**仅在 `commit()` 时**，见下方说明）：
  1. 排序 fdt / fdm / ivt
  2. flushStored  → {n}.fdt + {n}.fdx
  3. flushIndexed → {n}.fdm + {n}.tim + {n}.frq
  4. flushBkd     → {n}.bkd
  5. updateSegInfo(+1 段)
  6. 若 segCount > 1 且 enableMerge → IndexMerger 合并
```

`IndexWriter.commit()` 等待所有写入线程结束，做最后一次 flush，并持久化 `pk.map`。

> **没有内存阈值了。** 所有文档累积在内存中，直到 `commit()` 才一次性落盘——因此**每次 `IndexWriter` 会话只产出单一 segment**，
> 也不会触发段合并（`segCount > 1` 只可能来自"在已有索引上追加"这种跨会话场景）。
>
> 代价是**内存占用与语料规模成正比、且无上界**：`-Xmx` 必须放得下整个语料的内存表示
> （正排 + 倒排 + BKD 点 + `pk.map`）。这换来的是确定性的单段布局与无空洞的 docID——
> 实测 5 万篇时 `段数 = 1`、`preMaxID = 50000`（正好等于文档数）。
> 若将来需要在有限内存下建大索引，阈值机制需要重新引入。

### 1.4 检索链路

```
Searcher.search(Query, topN)
  ├─ TermQuery / StringQuery
  │    分词 → 内存 Term FST 定位 {n}.tim 偏移 → 读 {n}.frq posting → BM25 打分
  ├─ NumericRangeQuery
  │    {n}.bkd 树范围相交 → docID 集合
  ├─ 过滤：pk.map + deleted.ids
  └─ TopScoreDocCollector 收集 Top-N（同分提前终止）

Searcher.doc(ScoreDoc)
  └─ {n}.fdx 定位压缩块 → {n}.fdt LZ4 解压 → 反序列化 stored 字段
```

### 1.5 索引目录布局

一个索引 = 一个目录（如 `/opt/index/1/`）：

```
segment.info     段元数据（20 字节：日期、段数、preMaxID、格式版本）
pk.map           uniqueID ↔ 全局 docID 映射
deleted.ids      软删除主键集合
1.fdt / 1.fdx    stored 字段数据 / 块索引（LZ4，16 KiB 块）
1.fdm            字段元数据（BM25 平均字段长度）
1.tim / 1.frq    词项字典 / 倒排 posting 列表
1.bkd            数值字段 BKD 树（叶子 512 点）
```

格式细节（VInt 编码、各文件字段偏移、合并策略、限制）见 [`INDEX_FORMAT.md`](INDEX_FORMAT.md)。

### 1.6 分词模块

`NShortestPathAnalyzer` 是 Hawk 的中文分词器，也是与 Lucene 对比时的**关键公平性变量**：

- 基于词图 + N-最短路径（默认 `n=1`，即最短路）切分；
- 词典资源 `segment/src/main/resources/data/npathword.data`（约 5.7 MB）与 `punctuation.data`；
- 输出 `Term{field, value, pos}` 集合供索引与查询共用。

---

## 2. 构建与运行

**要求 JDK 22+**（当前使用 25 LTS），Maven。

> 为什么不能再低了：索引文件的寻址改用 `java.lang.foreign` 的 `MemorySegment` 以突破 2 GiB 单文件上限，
> 而 FFM 在 **JDK 22** 才转正（JEP 454）；JDK 17/21 上它只是 preview，且 `FileChannel.map(mode, long, long, Arena)`
> 这个签名在 22 才出现。`pom.xml` 中为 `maven.compiler.release=25`。
>
> 另外 JDK 23 起 javac 不再隐式运行"仅在 classpath 上"的注解处理器，因此根 pom 显式配了 `-proc:full`——
> 否则 Lombok 与 JMH 的 processor 会一起静默失效（表现为找不到 `@Data` 生成的成员、`benchmarks.jar` 缺 `BenchmarkList`）。

> **构建父 pom 是 `spring-boot-starter-parent` 4.1.1**（对应 Spring Framework 7）。
> 它对所有模块统一第三方依赖版本与插件配置；各模块按需显式声明，**多数并不引入 Spring 运行时**
> ——`segment` 是纯 Java，classpath 上只有 4 个 jar：`slf4j-api`（`@Slf4j`）、`lombok`、
> `fastjson`、`mysql-connector`（取其中的 `StringUtils`，不是当 JDBC 驱动用）。
>
> **Spring Boot 4.x 是 JDK 25 的下限**：2.7 / 3.x 内置的 ASM 读不了 class 文件 major 69，
> 会在组件扫描阶段直接抛 `Unsupported class file major version 69`，应用起不来。
> 想降级 Spring Boot 之前请先确认这一点。

```bash
# 全量构建。切换 JDK 版本后务必带 clean：增量编译会复用旧字节码，报出"假的成功"
mvn clean -DskipTests package
```

> **刚 clone 下来？** 仓库里有约 200 MB 的派生产物（模型权重、商品向量、倒排索引）不在 git 里。
> 一条命令搞定：
>
> ```bash
> JAVA_HOME=/path/to/jdk-25 ./runDemo.sh
> ```
>
> 详见 [§7 大文件与 clone](#7-大文件与-clone)。

示例类一览（`demo/src/main/java/demo`）：`demo` 模块中的 `main` 方法直接指向本地索引目录，按需修改路径后运行。

| 类 | 用途 |
|----|------|
| `BuildIndex` | **命令行建索引**：`demo.BuildIndex <corpus.csv> <index-dir>`，`runDemo.sh` 用的就是它 |
| `WirteIndex` / `WriteIndexFromFile` / `WriteIndexFromFile2` | 写索引 |
| `WriteNumericIndex` | 数值字段索引 |
| `SearchByTermQuery` / `SearchByStringQuery` / `SearchStringQuery2` | 词项 / 串检索 |
| `SearchByNumericRangeQuery` | 数值范围检索 |
| `DeleteDocDemo` | 软删除 |
| `FSTBuild` / `MemRandomAccess` | FST 与内存映射试验 |

---

## 3. 与 Lucene 的基准对比

用 **JMH 1.37** 在**同一份语料、同一分词器、同一 JVM 参数**下对比 Hawk 与 **Lucene 8.11.4**。

### 3.1 测评标准与公平性设计

| 维度 | 设置 |
|------|------|
| 语料 | `goods.csv`：中文电商商品标题，**100,000** 条，制表符分隔 `(uniqueID, title, price)` |
| 字段 | `title`(分词+存储)、`price`(数值点+存储)、`descript`(仅存储)、`digt`(仅存储)、`uniqueID`(主键) |
| **分词器** | **两者共用 Hawk 的 `NShortestPathAnalyzer`**：Lucene 侧通过 `HawkLuceneAnalyzer` + `HawkTokenizer` 适配器接入 |
| JVM | JDK 17.0.19，`-Xms2g -Xmx2g`，单 fork，`shouldDoGC(true)` |
| 机器 | 20 逻辑核 |
| 检索规模 | 索引 50,000 篇，32 条查询轮转，`topN = 500` |
| 查询构造 | 取标题前 2 个字符作为词项/串查询；`price` 落于 [1.0, 100.0] 的宽度 20 的随机区间（固定种子 42） |

**为什么这个对比有意义**：Lucene 侧不是用它自己的 `StandardAnalyzer`/`SmartChineseAnalyzer`，而是被替换成 Hawk 的分词器实现。因此两侧的**分词开销完全相同**，差异只来自索引结构、打分与存储层——这是能否公平对比的前提。

> **关于运行环境的两点说明**
>
> 1. `benchmark/results/` 里记录的结果是在 **JDK 17.0.19** 上跑出来的，当时工程还在用 `MappedByteBuffer`。工程迁移到 JDK 25 + FFM 之后，Hawk 侧检索基准已复测，吞吐与记录相当（Term 67.3k vs 66.3k、NumericRange 112.5k vs 108.5k ops/s，2 次迭代的噪声范围内），因此 §3.2 / §3.3 的数字仍可作为对照。**但表格里的 JVM 一栏描述的是当时那次运行**；现在重跑需要 JDK 22+。
> 2. 下面 §3.2 配置栏中的 `maxRamUsage=1 GiB` 是**当时那次运行的记录**。该配置项**现已整个移除**：不再有内存阈值，flush 只在 `commit()` 发生（见 §1.3）。因此今天的基准跑法与该记录已不完全相同，但 50k 篇的规模下两者都不会中途刷盘，对比仍然成立。

### 3.2 索引吞吐

平均写入耗时（`avgt`，ms/op，**越低越好**），含索引与提交：

| 文档数 | Hawk | Lucene | Hawk/Lucene | Hawk 吞吐 | Lucene 吞吐 |
|--------|------|--------|-------------|-----------|-------------|
| 1,000 | **94.3** | 224.7 | 0.42 | 10,600 docs/s | 4,450 docs/s |
| 5,000 | **322.8** | 348.3 | 0.93 | 15,489 docs/s | 14,354 docs/s |
| 10,000 | 590.1 | **486.8** | 1.21 | 16,946 docs/s | 20,542 docs/s |

配置差异（来自运行日志）：

- Hawk：`indexerThreadNum=10`，`maxRamUsage=1 GiB`，索引基准中 **`enableMerge=false`**；
- Lucene：`ramBufferSizeMB=256`，`useCompoundFile=false`，`ConcurrentMergeScheduler`（自动线程数）。

**解读**：

- 小数据量（1k）Hawk 领先，但该点受 JIT 预热影响最大，**不宜作为结论**。两端随规模增长都快速逼近稳态吞吐（~15–20k docs/s），说明固定开销被摊薄。
- 10k 时 Lucene 反超约 **1.2×**，这是本组数据中最可信的一点。原因是 Lucene 的 `ConcurrentMergeScheduler` 在后台多线程持续合并、把段数压得很低，而 Hawk 在这一组基准里**关闭了合并**，只承担写盘成本——两者并非在同一合并策略下对比，此处的差距**不能直接归因于底层实现优劣**。
- 结论应表述为：**在 1k–10k 商品文档量级上，Hawk 的写入吞吐与 Lucene 处于同一量级**，而非"Hawk 更快/更慢"。

### 3.3 检索吞吐

吞吐（`thrpt`，ops/s，**越高越好**）：

| 场景 | Hawk | Lucene | Hawk/Lucene |
|------|------|--------|-------------|
| `searchTerm`（单 term 查询） | **66,694** | 35,146 | **1.90×** |
| `searchString`（分词后布尔查询） | **55,175** | 31,186 | **1.77×** |
| `searchNumericRange`（数值范围） | **100,425** | 13,931 | **7.21×** |
| `searchTermAndFetchDoc`（检索 + 取原文） | **44,779** | 28,536 | **1.57×** |

**解读**：

- **词项检索（1.9×）**：Hawk 用内存 Term FST 直接定位 `{n}.tim` 偏移，再顺序读 `{n}.frq`；查询路径短、对象分配少。Lucene 的 `TermQuery` 虽然同样走 FST 词典，但其 `Scorer`/`Weight` 抽象层与 `LeafReader` 间接调用在同规模的短 posting 上开销更明显。
- **数值范围（7.2×）**：差距最大的一项。Hawk 的 `NumericRangeQuery` 直接对 `{n}.bkd` 做树相交，产出 docID 集合；Lucene 8.11 的 `DoublePoint.newRangeQuery` 同样基于 BKD，但默认 `maxExpansions=1024`，且其 `PointRangeQuery` 为每个命中构建迭代器并逐点检查 docID 是否存活，单次查询的固定成本更高。
- **取原文（1.57×）**：Hawk 通过 `{n}.fdx` 定位 LZ4 块后解压，Lucene 侧 `searcher.doc()` 需要跨 `StoredFields`/`CompressionMode` 多层。增幅低于纯检索，说明取原文环节两者成本接近，整体优势由检索环节带动。

### 3.4 复现方式

```bash
cd benchmark/scripts

# Hawk（默认 DOC_COUNTS="1000 5000 10000"，JMH_ARGS="-i 1 -wi 1 -f 1"）
./run-hawk-benchmark.sh

# Lucene
./run-lucene-benchmark.sh
```

可通过环境变量覆盖：`DOC_COUNTS`、`CORPUS_FILE`、`JMH_ARGS`、`HAWK_BENCHMARK_INDEX_DIR`、`LUCENE_BENCHMARK_INDEX_DIR`。

本次数据来自 `benchmark/results/`：

- `hawk-20260611-125505.txt`
- `lucene-20260611-125817.txt`

### 3.5 结果的局限（请务必连同数字一起引用）

1. **迭代次数极少。** 脚本默认 `-i 1 -wi 1 -f 1`（1 次热身、1 次测量、单 fork），JMH 输出的 `Error` 列为空即为该原因。**索引基准中 1k/5k 两点的差异不可信**，检索基准相对稳定但仍建议提高迭代次数后复测。
2. **合并策略不对等。** Hawk 索引基准用 `enableMerge=false`，Lucene 侧 `ConcurrentMergeScheduler` 正常工作，两者承担的合并成本不同。
3. **规模有限。** 索引基准最大 10k 篇、检索基准 50k 篇，未覆盖百万级。单文件 2 GiB 上限已随 FFM 迁移解除（见 [§5](#5-已知限制)），但**文档数 ~2^31 的上限未动**（posting 的 `VInt docID`、`preMaxID`、`pk.map` docID 仍是 `int`），到该量级需升 `formatVersion`。
4. **字段类型不完全等价。** Lucene 侧 `descript`/`digt` 以 `StoredField` 写入，Hawk 侧为 `Tokenized.NO` 的存储字段；`uniqueID` 在 Lucene 侧同时写入 `LongPoint` 与 `StoredField`（含未被查询使用的点索引）。
5. **单机单 JVM。** 未涉及跨机部署，与本项目"写入端/召回端可分离"的架构定位不完全对应。

---

## 4. 向量召回

`vector/` 是一条**独立的稠密向量召回链路**，与倒排索引并列（不是它的补充）。它**不依赖 `core` / `segment` / `indexer` / `recall` 中的任何一个**——将来要做向量 + BM25 混合召回时，才需要引入依赖。

```
商品向量 (.npy) ──► JVectorIndex (HNSW) ──┐
                                          ├─► top-K ──► ordinal ──► 商品 ID
query 文本 ──► TextEncoder (ONNX) ──► float[] ─┘
```

### 4.1 核心设计：query 向量化 ←→ 商品向量库 的解耦

解耦边界定义在 `VectorIndex` 这一个接口上（`vector/index/VectorIndex.java`）：

```java
public interface VectorIndex extends Closeable {
    int size();
    int dimension();
    SearchHits search(float[] query, int topK);              // 不带过滤
    SearchHits search(float[] query, int topK, Bits acceptOrds);  // 带过滤
}
```

**参数是 `float[]`，不是文本，也不是任何模型类型。** 索引只回答"离这个向量最近的 top-K 是谁"，完全不关心这个向量是 bge 编的、别的模型编的、还是合成数据。

这条边界带来三件事：

- **索引可以被别的东西复用**，也可以**用合成向量独立测试**——不需要加载模型就能验证索引本身；
- **换模型不动索引代码**，换索引实现（例如换 Lucene 以获得向量 + BM25 同引擎混合召回）调用方不动；
- **"模型与索引是否匹配"由调用方校验**，不是索引层的职责——索引层也拿不到这个信息（它只知道维度和 `float[]`）。

于是模块天然分成两半，各自独立演进：

| 半边 | 类 | 职责 |
|---|---|---|
| **query 向量化** | `model/TextEncoder` + `model/ModelDescriptor` | 文本 → L2 归一化向量；完全由 `model.json` 驱动，对具体模型零假设 |
| **商品向量库** | `index/JVectorIndex` + `index/NpyReader` | 商品向量 → HNSW 索引 → top-K |

**把两者接起来是调用方的职责**——`Demo` 就是这么做的，见 §4.5。

### 4.2 支持的模型文件格式

一个模型 = 一个目录，含描述符 + ONNX 图 + tokenizer：

```
model/bge-small-zh-v1.5/
├── model.json        # 描述符：Java 侧唯一的可变配置来源
├── encoder.onnx      # ONNX 图（pooling 与 L2 归一化已烘进图里）
└── tokenizer.json    # HF tokenizer（WordPiece / BPE / Unigram）
```

`model.json` 字段：

| 字段 | 含义 |
|---|---|
| `name` | 模型标识 |
| `fingerprint` | 权重指纹（`sha256:...`）。**调用方据此校验"索引与模型是否匹配"** |
| `dim` | 向量维度 |
| `pooling` | 池化方式（已烘在图里，此处仅作记录） |
| `onnx.file` / `inputNames` / `outputName` | ONNX 图文件名，及图**实际**具有的输入输出名 |
| `tokenizer.file` | `tokenizer.json` |
| `preprocess.maxLenQuery` / `maxLenDoc` | 两侧长度上限（非对称模型不同） |
| `preprocess.queryPrefix` / `docPrefix` | 两侧前缀（E5 这类非对称模型需要） |
| `preprocess.encodeBatchSize` | 编码分块大小，默认 64 |

实际使用的 `model/bge-small-zh-v1.5/model.json`：

```json
{
  "name": "BAAI/bge-small-zh-v1.5",
  "fingerprint": "sha256:e1429e5b64154dc3687fd726f507ae604d281afd28ca2d5fb3750c9ad152c326",
  "dim": 512,
  "pooling": "cls",
  "onnx": {
    "file": "encoder.onnx",
    "inputNames": ["input_ids", "attention_mask", "token_type_ids"],
    "outputName": "vector"
  },
  "tokenizer": { "file": "tokenizer.json" },
  "preprocess": { "maxLenQuery": 32, "maxLenDoc": 64, "queryPrefix": "", "docPrefix": "", "encodeBatchSize": 64 }
}
```

**"支持任意模型"靠的是把假设全部外置**：

- 维度、输入名、长度上限、前缀**全部从描述符读**，Java 代码里没有任何与具体模型相关的常量；
- **只喂图实际具有的输入**——BERT 系含 `token_type_ids`，XLM-R 系不含，代码不会为"统一"而塞空壳张量；
- 分词交给 DJL `HuggingFaceTokenizer` 直接读 `tokenizer.json`，**原生支持 WordPiece / BPE / Unigram**，不需要 Java 侧实现分词算法；
- **pooling 与 L2 归一化烘在 ONNX 图里，Java 侧不做任何后处理**——否则每支持一个新模型都要加一段 Java 逻辑，"任意模型"就无从谈起；
- 启动时校验描述符声明的输入输出与图实际一致，**对不上就启动即失败**，避免一路跑到检索结果变垃圾才被发现。

> 模型目录由 Python 侧 `hawk_vector.export.export_onnx` 产出（不在此仓库）。`model/` 已在 `.gitignore` 中。

### 4.3 商品向量文件格式

用 NumPy `.npy`——语料向量由 Python 编码产出，直接读可省掉一次格式转换和中间产物：

```
model/zero_shot_bge_small/
├── corpus_emb.npy       # (100001, 512) float16 —— goods.csv 每条 title 的向量
└── corpus_emb_ids.npy   # (100001,)     int64   —— 行号 → 商品 ID 的映射
```

**商品库与倒排链路统一为 hawk 主仓库的 `goods.csv`**：取 `title` 列编码成向量，第一列（`id`，0-based，0…100000）作为商品 ID。

由此得到一个有用的事实：**`ids[i] == i`，即 ordinal 本身就等于商品 ID**（恒等映射）。`Demo` 里仍保留 `ids[ordinal]` 这层查表，因为换成语料 ID 非顺序的数据集时它就不能省。

`.npy` 结构（v1.0 / v2.0 均支持）：

```
6 字节    magic "\x93NUMPY"
2 字节    版本 (major, minor)
2 或 4     header 长度（小端；v1.0 为 2 字节，v2.0 为 4 字节）
n 字节    header：Python dict 字面量，如
          {'descr': '<f2', 'fortran_order': False, 'shape': (100001, 512), }
...       数据（64 字节对齐后开始）
```

支持的 dtype：

| 文件 | 可用 dtype | 说明 |
|---|---|---|
| `corpus_emb.npy` | `<f2` / `<f4` | **一律读成 fp32 输出**（fp16 自动转换） |
| `corpus_emb_ids.npy` | `<i8` / `<i4` | 一维，列数必须为 1 |

**如何生成**：由 Python 侧的 **Hawk-Vector** 项目（与本仓库平级）编码产出：

```bash
cd /opt/java_project/Hawk-Vector
./scripts/encode.sh configs/zero_shot_bge_small.yaml \
    --corpus  /opt/java_project/Hawk/goods.csv \
    --out     /opt/java_project/Hawk/model/zero_shot_bge_small/corpus_emb.npy
# 用时约 18s（RTX 3060，100,001 篇，~5,900 doc/s）
```

几个要点：

- `--corpus` 可以传**任意 `id \t 文本` 的文件**，多出来的列会被忽略——所以 `goods.csv`（`id \t title \t price`）**不需要做格式转换**。
- `ids` 的起点**不要求是 1**：Hawk-Vector 里 `Corpus` 会推出连续区间的起点（ecom 是 1、goods.csv 是 0），两者都走 O(1) 位置索引；区间有空洞或重复时自动回退到 dict。
- `configs/` 与本命令**无关**——那个配置是给 ecom 的 dev/test 评测用的，改它会破坏评测的语料对应关系，所以生成商品向量走独立的一次性命令。
- 长度上限沿用 config 里的 `max_len_doc: 64`。goods.csv 标题 **p99 恰好也是 64 字**，因此只有约 1% 被截断，无需调整。

### 4.4 ordinal 与业务主键

`SearchHits` 返回的是 **ordinal（向量在索引里的行号）**，不是业务主键：

```java
public record SearchHits(int[] ordinals, float[] scores)   // 按分数降序
```

**建索引时按什么顺序喂入向量，ordinal 就是什么顺序。** `JVectorIndex` 不做任何主键映射——调用方要保持"喂入顺序"与自己的主键表一致。这正是 `corpus_emb_ids.npy` 必须与 `corpus_emb.npy` **配套**的原因：只有 `ids[ordinals[i]]` 才是商品 ID。

#### 拿到商品 ID 之后：取回商品原文

商品原文存在**倒排那一侧**的索引里，用 `Searcher.docByUniqueId(long)` 取：

```java
Document doc = searcher.docByUniqueId(productId);          // productId = ids[ordinals[i]]
String title = ((StringField) doc.getFieldMap().get("title")).getValue();
```

**参数是商品唯一 ID（`PrimaryKeyField` 的值，即 `pk.map` 的 key），不是全局 docID。** 两者数值上没有任何关系——全局 docID 由索引线程的**完成顺序**决定，不是提交顺序（实测：商品 ID `0` 落在 docID `2` 上、商品 ID `23198` 落在 docID `23191` 上）。

这个区别很危险，因为**混用不会报错**：两者数值区间重合，传错了索引越界检查过不去。

```
正确用法  docByUniqueId(23198) → PVC直插管g件110变75缩口排水弯头…
错误用法  doc(new ScoreDoc(0f, 23198)) → 反监控防追踪反窃听私密的轿车防偷拍…
```

两个完全不同的商品，**但调用不报任何错**。所以 `Searcher` 只暴露按业务主键取文的方法，不鼓励直接拼 `ScoreDoc`。

**返回值**：`null` 表示该 ID 不存在、或对应文档已被**软删除**。注意删除不会从 `pk.map` 抹掉条目（靠 `deleted.ids` 在检索时过滤），所以"ID 存在于 pk.map"不等于"文档可读"。

于是向量链路与倒排链路的完整闭环是：

```
query 文本 → TextEncoder → 向量 → JVectorIndex → ordinal → ids[ordinal] = 商品ID
                                                                    ↓ pk.map
                                              商品原文 ← docByUniqueId ←┘
```

### 4.5 演示代码

`vector/src/main/java/hawk/vector/demo/Demo.java`，端到端把每一块串起来：

```java
public class Demo {
    private static final String VECTOR_DIR = "model/zero_shot_bge_small";
    private static final String MODEL_DIR = "model/bge-small-zh-v1.5";

    public static void main(String[] args) throws Exception {
        // 查询词可由命令行覆盖；默认取 goods.csv 里真实存在的商品词
        String text = args.length > 0 ? args[0] : "老黄冰糖";
        int topK = args.length > 1 ? Integer.parseInt(args[1]) : 10;

        // 1) 读商品向量，以及 行号 → 商品 ID 的映射（全部 100,001 条）
        float[][] matrix = NpyReader.readFloatMatrix(Path.of(VECTOR_DIR, "corpus_emb.npy"));
        long[] ids = NpyReader.readLongArray(Path.of(VECTOR_DIR, "corpus_emb_ids.npy"));
        System.out.printf("商品向量 %d × %d%n", matrix.length, matrix[0].length);

        // 2) 建索引
        try (JVectorIndex index = JVectorIndex.build(matrix)) {
            // 3) 加载模型，把 query 文本编码成向量
            try (TextEncoder encoder = new TextEncoder(Path.of(MODEL_DIR))) {
                float[] query = encoder.encodeOne(text, Role.QUERY);

                // 4) 检索
                SearchHits hits = index.search(query, topK, Bits.ALL);

                // 5) ordinal 映射回商品 ID
                int[] ordinals = hits.ordinals();
                for (int i = 0; i < ordinals.length; i++) {
                    long productId = ids[ordinals[i]];
                    System.out.printf("  #%d  %.4f  ordinal=%-6d 商品ID=%d%n",
                            i + 1, hits.scores()[i], ordinals[i], productId);
                }
            }
        }
    }
}
```

注意第 2 步和第 3 步是**两个彼此独立的资源，生命周期互不相干**——索引不需要模型，模型也不需要索引，`Demo` 只是把两者接起来。**这就是 §4.1 那条解耦边界在代码上的体现。**

运行（工作目录须为仓库根，`model/` 在根下）：

```bash
mvn -q -pl vector -am -DskipTests package
mvn -q -pl vector dependency:build-classpath -Dmdep.outputFile=/tmp/vcp.txt
java -Xmx3g -cp "vector/target/classes:$(cat /tmp/vcp.txt)" hawk.vector.demo.Demo
java -Xmx3g -cp "vector/target/classes:$(cat /tmp/vcp.txt)" hawk.vector.demo.Demo "镀锌弯头" 5
```

> 堆要给到 3g：100,001 × 512 读成 fp32 后是约 205 MB，加上 jvector 的图结构会更高；
> `NpyReader` 会在读之前预检堆空间，不够会给出可操作的提示而不是直接 OOM。

实测输出（本机 RTX 3060，商品库为 goods.csv 全部 100,001 条）：

```
[建索引] 100,001 条 × 512 维  M=16 efC=100  用时 15.9s
查询「老黄冰糖」top-10：
  #1  0.9197  ordinal=49732  商品ID=49732      → 老冰糖黄冰糖散装5斤甘蔗老式冰糖碎2500克多晶冰糖特级正宗小粒
  #2  0.8817  ordinal=43621  商品ID=43621      → 富昌银京黄冰糖400g/袋老冰糖多晶冰糖烘焙原料茶饮甜汤甜品
  #3  0.8785  ordinal=23888  商品ID=23888      → 太古黄冰糖1kg*2袋 食用糖烹饪红烧肉煲汤煮粥糖水柠檬酵素青梅酱
  #4  0.8641  ordinal=30954  商品ID=30954      → 广西柳冰黄冰糖泡酒孝酵素多晶冰糖中冰红糖块【整袋30斤包邮】
  ...
```

（`→` 后的商品标题是事后用 `goods.csv` 第 `商品ID` 行对出来的，`Demo` 本身只打印 ID。）

### 4.6 索引实现与依赖

`JVectorIndex` 基于 **jvector 4.0.1**（纯 Java、无 JNI，HNSW + 磁盘驻留 + PQ/BQ 量化）。默认参数：`M=16`、`efConstruction=100`、`rerankK=256`、相似度函数为**余弦**。

关键依赖：

| 依赖 | 用途 |
|---|---|
| `com.microsoft.onnxruntime:onnxruntime` | 推理。要 GPU 换成 `onnxruntime_gpu` |
| `ai.djl.huggingface:tokenizers` | 分词，JNI 封装 Rust `tokenizers`，直接读 `tokenizer.json` |
| `io.github.jbellis:jvector` | ANN 索引 |
| `com.fasterxml.jackson.core:jackson-databind` | 读 `model.json`（版本由 Spring Boot 统一管理） |

### 4.7 当前状态

- 模块可端到端跑通（§4.5 实测），商品库已与倒排链路**统一为 `goods.csv`**。
- **尚未与倒排做混合召回**，也没有接入 `recall` 的检索链路——目前是独立链路。两条链路现在共享同一套商品 ID（`goods.csv` 第一列），做归并时主键是对得上的。
- `VectorIndex` 与 `TextEncoder` 里各留了一个 `main` 方法，是硬编码路径的临时调试脚手架，且 `catch` 块吞掉了异常（只打印 "model loading failed"）；正式用法请参照 `Demo`。
- `model/` 目录不在版本控制内，需要自行从 Python 侧导出（见 §4.3）。
- `model/zero_shot_bge_small/` 里还留着 `dev_pred.*` / `test_pred.tsv` —— 那是**旧的 ecom 语料**的评测产物，与现在的 `corpus_emb.npy`（goods.csv）已经不配套，需要时请重新跑评测生成。

---

## 5. 已知限制

1. **单文件 2 GiB 上限已解除（前提：JDK 22+）**：索引文件改用 `MemorySegment`（FFM）以 **`long`** 寻址，单次 `FileChannel.map()` 不再受 `Integer.MAX_VALUE` 约束；映射生命周期由 `Arena` 管理，取代了原先 `sun.misc.Cleaner` 反射那套 hack。

   偏移在磁盘上本就是 VLong（64 位），原先被窄化成**有符号 `int`** 的地方——posting 偏移、`.bkd` 节点偏移、`.fdt` 块偏移、`.fdx`/`.fdm` 的整文件大小——现已全部改为 `long`。

   **已验证**：`1.fdt` = 2.26 GiB 的真实索引可正常打开、检索、取回原文；而旧实现在同一文件上 `fc.map(READ_ONLY, 0, 2_426_542_857L)` 会抛 `IllegalArgumentException: Size exceeds Integer.MAX_VALUE`。`core` 的 `LargeOffsetTest` 另覆盖 2 GiB 之后 VInt/VLong/bytes 的读写往返。

   **注意**：FFM 有两个容易静默出错的坑，代码里用 `JAVA_INT_BE` / `JAVA_LONG_BE` 显式规避——`ValueLayout.JAVA_INT` 默认是 **native order**（x86 小端）而格式是大端；且默认要求 4 字节对齐而格式是紧凑排布（如 `.fdm` 的 `Byte fieldType` 后紧跟 `Int`）。

2. **文档数上限约 2^31**：posting 里的 `VInt docID`、`SegmentInfo.preMaxID`、`pk.map` 的 docID 仍是 `int`。这与文件大小无关，达到该量级需升 `formatVersion` 并加宽 docID。
3. **搜索只读 `1.*`**：若 `enableMerge=false` 导致存在 `2.*` 段，未合并数据对搜索不可见。
4. **Term FST 不落盘**：每次打开 `DirectoryReader` 都从 `1.tim` 重建，大索引有额外打开开销。
5. **docID 从 1 开始**：`docIDAllocator` 先自增再赋值，首篇文档全局 ID = `docBase + 1`。
6. **数值索引**：当前格式（`formatVersion = 1`）下 `DoubleField` 只写 BKD，不写 tim/frq。
7. **向量召回未实现**：仅倒排索引链路可用。
8. **`pk.map` / `deleted.ids` 仍是全量进内存的 `HashMap`/`HashSet`**：文件本身已改为流式读取（不再整文件进堆），但内存占用随文档数线性增长，这是比文件大小更早到来的瓶颈。

---

## 6. 目录结构

```
Hawk/
├── core/           Directory、文件格式、编解码、BKD、字段模型
├── segment/        中文分词（N-最短路径）与词典资源
├── indexer/        IndexWriter、DocWriter、IndexMerger
├── recall/         DirectoryReader、Searcher、Query、Similarity
├── demo/           使用示例
├── vector/         稠密向量召回（独立模块，详见 §4）
│   └── src/main/java/hawk/vector/
│       ├── model/  ModelDescriptor（model.json）、TextEncoder（ONNX + 分词）
│       ├── index/  VectorIndex（解耦边界）、JVectorIndex、NpyReader、SearchHits
│       └── demo/   Demo（端到端示例）
├── benchmark/      JMH 基准（Hawk vs Lucene）
│   ├── scripts/    run-hawk-benchmark.sh / run-lucene-benchmark.sh
│   └── results/    历史基准结果
├── goods.csv       基准语料（10 万条商品标题）
├── model/          模型与商品向量（大文件不进 git，见 §7）
├── runDemo.sh      一条命令把仓库变成可运行状态（见 §7）
├── index-data/     倒排索引（派生，已 gitignore）
├── INDEX_FORMAT.md 索引文件格式说明
└── README.md
```

---

## 7. 大文件与 clone

仓库里有 200 MB 量级的派生产物（模型权重、商品向量、倒排索引）。它们不该进 git，但 clone 的人又应该能直接用——办法是**分清哪些能再生、哪些不能**。

### 8.1 先看体积与来源

| 路径 | 体积 | 能否从 clone 后的仓库再生 |
|---|---|---|
| `goods.csv` | 4.7 MB | **已跟踪**，是真正的源头 |
| `index-data/goods/` | 21 MB | ✅ 纯 Java，**5 秒** |
| `model/*/*/model.json` | 517 B | ⚠ 本身很小，但**在 `model/` 下，不跟踪** |
| `model/zero_shot_bge_small/corpus_emb.npy` | 98 MB | ✅ 有模型就行，**18 秒**（GPU） |
| `model/bge-small-zh-v1.5/tokenizer.json` | 432 KB | ✅ HuggingFace 上直接下 |
| **`model/bge-small-zh-v1.5/encoder.onnx`** | **91 MB** | ❌ **需要 Python 环境导出** |

**结论：真正需要托管的只有 `encoder.onnx` 一个文件。** 其余 120 MB 都能从 `goods.csv` 加模型快速再生。

整个 `model/` 目录（约 190 MB）都不进 git，规则就一行——注意**前导斜杠不能省**：

```gitignore
/model/
```

不带斜杠的 `model/` 会匹配**任意层级**的同名目录，曾把 `vector/src/main/java/hawk/vector/model/` 里的两个源文件一起吞掉，导致 clone 下来的仓库编译不过。这是 gitignore 里最容易踩的一类坑。

> 想让 `model.json`（517 字节的描述符：维度、输入名、长度上限、前缀）单独进 git 也可以，但那需要**逐层放行**——git 不允许在已排除的父目录下再包含文件：
>
> ```gitignore
> /model/**
> !/model/**/
> !/model/**/model.json
> ```
>
> 本项目没有这么做：`runDemo.sh` 会从 Release 解压出完整的 `model/`，描述符跟着一起来。

### 8.2 三种做法

| 方案 | clone 后能否直接用 | 代价 |
|---|---|---|
| **Git LFS** | 装了 LFS 就透明 | GitHub 免费额度 **1 GB 存储 / 1 GB 月流量** —— 200 MB 一次 clone，每月只够约 4 次 |
| **Release 附件 + 脚本**（推荐） | 跑一条 `bootstrap.sh` | 公开仓库的 Release 附件**没有流量限制** |
| **完全自建** | 需要 Python + torch | 仓库最干净，但门槛高 |

### 8.3 推荐的组合

```bash
git clone https://github.com/NoobCoderGrowing/Hawk.git
cd Hawk
JAVA_HOME=/path/to/jdk-25 ./runDemo.sh
```

`runDemo.sh` 依次做：检查 JDK（≥22，否则给出可操作提示）→ 编译 → **用 `goods.csv` 建倒排索引**（5 秒，不需要网络）→ 检查 `model/`，缺失则从 Release 下载 → 启动演示服务（前台运行）。

当前资产包：

| | |
|---|---|
| Tag | `demoDependency` |
| 资产 | `model.tar.gz`（149.9 MB） |
| 地址 | `https://github.com/NoobCoderGrowing/Hawk/releases/download/demoDependency/model.tar.gz` |
| 内容 | `model/bge-small-zh-v1.5/` + `model/zero_shot_bge_small/`（11 个条目） |

**重新打包**（在仓库根，注意要保留 `model/` 顶层前缀）：

```bash
tar -czf model.tar.gz model/bge-small-zh-v1.5 model/zero_shot_bge_small
```

> 打包时**别 `cd model` 再打包** —— 那样归档里没有 `model/` 前缀，`runDemo.sh` 解压后会落在错误位置。
> 脚本里那条 `tar -xzf ... -C "$ROOT"` 就是按"带前缀"写的。

换托管位置用 `HAWK_ASSETS_URL` 覆盖：

```bash
HAWK_ASSETS_URL=https://your-host/model.tar.gz ./runDemo.sh
```

### 8.4 想进一步瘦身

- **`encoder.onnx` 转 fp16**：91 MB → 约 46 MB。ONNX Runtime 支持 fp16 推理，但要在 Hawk-Vector 的导出脚本里加转换，并验证向量质量没有下降。
- **演示用不着全量 10 万商品**：生成一个 1 万条的子集语料，向量从 98 MB 降到 10 MB。索引与向量都从子集建即可（两边必须同源）。对演示效果几乎没有影响。

两者叠加可以把总包压到 60 MB 以内，LFS 也能轻松承载。