# Hawk

Hawk 是一个**垂直搜索引擎**的检索内核实现：写入端（indexer）与召回端（recall）解耦，支持中文分词、倒排索引、BM25 打分、数值范围查询与主键级别的软删除。

召回侧有**两条链路**：倒排索引（§1）与向量索引（§4）。`core` / `segment` / `indexer` / `recall` / `vector` 是**单机库**；`cloud-*` 三件套在其上做分布式分片与多路融合（§1.7）。

> **单机链路**：`core` / `segment` / `indexer` / `recall` / `demo` / `benchmark`
> **向量链路**：`vector`（依赖 `core`；与 `indexer` 定位一致的单机索引 SDK）
> **分布式链路**：`cloud-coordinator` / `cloud-indexer` / `cloud-recall`

> 索引文件格式的逐字节说明见 [`INDEX_FORMAT.md`](INDEX_FORMAT.md)。
>
> 参与开发请联系：wenjun.yao@uqconnect.edu.au

---

## 1. 项目架构

### 1.1 模块划分

Maven 多模块工程，`core` 位于依赖链底部：

```
hawk (pom)
├── core              基础层：Directory / 文件格式 / 编解码 / BKD 树 / 字段模型
├── segment           中文分词：N-最短路径分词、词图、标点与词典资源（纯 Java，不依赖任何框架）
├── indexer           写入层：IndexWriter / DocWriter / IndexMerger
├── recall            召回层：DirectoryReader / Searcher / Query / Similarity / IndexAdmin
├── demo              示例与命令行入口：BuildIndex（建索引）、检索、删除文档
├── vector            向量索引 SDK：ONNX 文本编码 + HNSW 索引（依赖 core，详见 §4）
├── cloud-indexer     分布式索引器：把建索引包装成服务，按分片产出并登记到注册中心
├── cloud-recall      分布式召回：定时拉取分片索引，本地检索（文本 / 向量两个 role）
└── cloud-coordinator 协调器：派发建索引、跟踪轮次、扇出检索并做多路融合
```

依赖关系：

```
              recall  indexer        vector
                 │    ╱   │            │
                 │  ╱     │            │
              segment   core ◄──────────┘
```

- `indexer` 依赖 `core` + `segment`；`recall` 依赖 `core` + `segment`；**`indexer` 与 `recall` 之间无直接依赖**，二者仅通过磁盘上的索引目录通信——这正是"写入端与召回端解耦"在代码层面的体现：可以分别部署到不同进程/机器。
- `recall` 额外引入 `io.github.noobcodergrowing:JFST`，用于内存中的 Term FST。
- **`vector` 依赖 `core`**（复用 `Document` / `Field` 与 `directory.PkMapStore`），与 `indexer` 定位一致：单机、单进程、无分片，只是产出**向量索引**而不是倒排。它不依赖 `segment` / `indexer` / `recall`。

> `benchmark` 模块仅用于性能测评，不属于运行时依赖链，见 [§3](#3-与-lucene-的基准对比)。

### 1.2 核心抽象

| 抽象 | 位置 | 职责 |
|------|------|------|
| `Directory` / `FSDirectory` / `MMapDirectory` | `core/directory` | 索引目录抽象；MMap 实现用于只读映射段文件 |
| `Document` / `Field` | `core/document`、`core/field` | 文档与字段模型：`StringField`、`NumberField`、`PrimaryKeyField` |
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

### 1.7 分布式层（cloud-*）

上面五个模块都是**单机库**。分布式能力由三个 `cloud-*` 模块提供，它们不是新的索引实现，只是把 `indexer` / `recall` / `vector` 包装成服务并拉起协调：

| 模块 | 职责 |
|------|------|
| `cloud-indexer` | 建索引服务：`POST /index/build` 按分片产出索引，登记到注册中心（`JRegistry`） |
| `cloud-recall`   | 召回服务：定时从注册中心发现新轮次，**拉取**分片索引后本地检索；文本 / 向量各是一个 role |
| `cloud-coordinator` | 协调器：派发建索引、跟踪轮次收敛、把查询扇出到各分片，再**多路融合** |

几条与单机库不同的约定，读代码前先知道：

- **拉取，不是推送。** 索引器只把"最新轮次"登记到注册中心，召回端定时去拉。推送模型（`IndexPusher` / `/repush` / `/indexComplete`）已整套删除。
- **协调器归并用 RRF（倒数排名融合），不是分数相加**，并按业务主键去重。跨 role（BM25 vs 余弦）与跨分片（BM25 用的是本分片的 `df` / `avgDocLength`）的分数都不可比，只能看排名。
- 因此**多路时 `totalHits` 只是上界**（`totalExact=false`），单路且各分片都精确时才是精确值。
- 召回的契约是"**本分片前 N**"；索引未就绪的分片返回 **503**（与"没匹配"区分开）。
- 设计文档见 `INDEX_BUILD_DISPATCH_DESIGN.md`、`INDEX_PULL_MODEL_DESIGN.md`、`CLOUD_RECALL_SEARCH_DESIGN.md`。

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

> **刚 clone 下来？** 仓库里有约 190 MB 的模型权重与商品向量不在 git 里，已打包成根目录的 `model.tar.gz`：
>
> ```bash
> tar xzf model.tar.gz      # 解出 model/：模型权重（bge-small-zh-v1.5）+ 商品向量
> ```
>
> 模型目录的内容与导出方式见 §4.6。倒排索引目录 `index-data/` 同样不入 git，由 `demo.BuildIndex` 现场生成。

示例类一览（`demo/src/main/java/demo`）：路径基本写死，工作目录须为**仓库根**。

| 类 | 用途 |
|----|------|
| `BuildIndex` | 建倒排索引：读 `goods.csv` 写出 `index-data/goods`（每次清空重建）。主键用行号计数器，**不读 CSV 第一列** |
| `RecallDemo` | 检索示例：打开 `index-data/goods`，演示 `Condition`→`Query` 复杂请求（嵌套 AND/OR、FILTER、MUST_NOT） |
| `FSTBuild` / `MemRandomAccess` | FST 与内存映射试验 |

> 两者是**配对**的：先跑 `BuildIndex` 再跑 `RecallDemo`。建向量索引没有对应的独立示例，走 `VectorIndexWriter` / `VectorSearcher`（§4.4 / §4.5），或直接用 `cloud-indexer` / `cloud-recall`。

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

- Hawk：`indexerThreadNum=10`，索引基准中 **`enableMerge=true`**；
- Lucene：`ramBufferSizeMB=256`，`useCompoundFile=false`，`ConcurrentMergeScheduler`（自动线程数）。

**解读**：

- 小数据量（1k）Hawk 领先，但该点受 JIT 预热影响最大，**不宜作为结论**。两端随规模增长都快速逼近稳态吞吐（~15–20k docs/s），说明固定开销被摊薄。
- 10k 时 Lucene 反超约 **1.2×**，这是本组数据中最可信的一点。原因是 Lucene 的 `ConcurrentMergeScheduler` 在后台多线程持续合并、把段数压得很低。
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
3. **规模有限。** 索引基准最大 10k 篇、检索基准 50k 篇，未覆盖百万级。单文件 2 GiB 上限已随 FFM 迁移解除，但**文档数 ~2^31 的上限未动**（posting 的 `VInt docID`、`preMaxID`、`pk.map` docID 仍是 `int`），到该量级需升 `formatVersion`。
4. **字段类型不完全等价。** Lucene 侧 `descript`/`digt` 以 `StoredField` 写入，Hawk 侧为 `Tokenized.NO` 的存储字段；`uniqueID` 在 Lucene 侧同时写入 `LongPoint` 与 `StoredField`（含未被查询使用的点索引）。
5. **单机单 JVM。** 未涉及跨机部署，与本项目"写入端/召回端可分离"的架构定位不完全对应。

---

## 4. 向量索引

`vector/` 是一个**单机向量索引 SDK**，与 `indexer` 定位对等：把文档变成**向量索引**而不是倒排。它**依赖 `core`**（复用 `Document` / `Field` / `directory.PkMapStore`），但不依赖 `segment` / `indexer` / `recall`。

```
Document ──► VectorIndexWriter ──► 每字段一张 HNSW 图 + pk.map + 原文
                    │
              TextEncoder (ONNX)   文本 → L2 归一化向量

query 文本 ──► TextEncoder ──► float[] ──► VectorSearcher ──► VectorHit(业务主键, 分数)
```

### 4.1 定位：与 indexer 对等的单机索引 SDK

模块骨架照抄 `indexer`，对应关系一目了然：

| 倒排侧 | 向量侧 |
|---|---|
| `indexer.writer.IndexWriter` | `hawk.vector.writer.VectorIndexWriter` |
| `indexer.writer.config.IndexConfig` | `hawk.vector.writer.config.VectorIndexConfig` |
| `segment` 的 `Analyzer`（文本 → 词项） | `hawk.vector.model.TextEncoder`（文本 → 向量） |
| `recall.search.Searcher` | `hawk.vector.search.VectorSearcher` |
| `recall.search.ScoreDoc` | `hawk.vector.search.VectorHit` |

**最关键的改动：检索返回的是业务主键，不是 ordinal。** 旧实现只返回 ordinal（"向量在索引里的行号"），逼每个调用方自己去查 `ids[ordinal]` 那张表；改成多字段之后每张图各有自己的 ordinal 空间，那种映射根本无从写起。既然建索引时就知道主键，就该在 `VectorHit` 里换好：

```java
public record VectorHit(long uniqueId, float score)   // uniqueId = 建索引时从 PrimaryKeyField 取到的业务主键
```

> 旧设计里那条"向量侧不依赖 Hawk 任何模块、`.npy` 商品向量库 + ordinal 映射"的路子已**整体作废**：输入模型要和 `indexer` 共用（`Document` / `Field`），主键映射要复用 `directory.PkMapStore`。`util/NpyReader` 还留在模块里，但**当前已无任何调用方**。

### 4.2 一个字段 = 一张独立的 HNSW 图

不能把多个字段的向量塞进同一张图：HNSW 用**余弦距离**做近邻判断，不同字段的向量混在一起会互相做邻居、污染召回。这是结构性的，调参救不了 —— 也是 Qdrant 要 named vectors、Elasticsearch 要多个 `dense_vector` 字段的原因。

字段集由 `VectorIndexConfig` 显式声明，**同时是白名单**：没列的字段不进索引。多字段检索时每个字段各查一遍，再按权重把分数加起来：

- **融合用加权线性，不用 RRF。** RRF 的意义是规避不可比的分数（BM25 与余弦量纲不同）；而这里所有字段出自同一个模型，余弦分数天然同量纲，加权线性既简单又不丢信息。
- **query 只编码一次**：所有字段共用一个模型，同一个查询向量可以直接喂给每张图。
- **每张图各存一份 `pk.map`**：缺这个字段的文档不进这张图，各图的 ordinal 空间并不一致，不能共用一张对照表。

实测（10 万 × 512，本机）：单图检索 1.75 ms，两图 3.9 ms —— **融合本身的开销在噪声内**。

### 4.3 索引目录布局

```
<index-dir>/
├── meta.json            这个索引"是什么"（模型指纹、维度、字段集、格式版本）
├── title/               一个字段一个子目录
│   ├── graph.idx        HNSW 图
│   └── pk.map           复用 directory.PkMapStore：主键 → 该图内的 ordinal
├── descript/
│   ├── graph.idx
│   └── pk.map
├── stored.fdt           原文（LZ4 压缩块，顺序拼接）
├── stored.fdx           块索引
└── stored.fdm           字段元数据
```

`meta.json` 存在的理由：索引文件本身只存图，**不存模型指纹、不存字段集**。没有元数据，加载时就无从判断"这个索引和当前模型配不配"—— 配错不报错，只会一路跑出垃圾结果。

### 4.4 建索引：`VectorIndexWriter`

```java
var config = new VectorIndexConfig(List.of("title", "descript"))   // 字段集 = 白名单
        .setStoredFields(List.of("title", "descript"))             // 一并存原文（可空）
        .setWeights(Map.of("title", 0.7f, "descript", 0.3f));      // 融合权重，会被归一化

try (var w = new VectorIndexWriter(config, Path.of("index-data/goods-vec"))) {
    for (Document d : docs) {
        w.addDoc(d);        // 取字段文本 → ONNX 编码；攒够 512 条编一批
    }
    w.commit();             // 按字段建图 + 落盘
}
```

`VectorIndexConfig` 的旋钮（方法链式，对标 `IndexConfig`）：

| 方法 | 默认 | 说明 |
|---|---|---|
| `VectorIndexConfig(List<String> fields)` | — | **必填**，同时是白名单 |
| `.setPrimaryKeyFieldName(String)` | `uniqueID` | 与 `IndexConfig` 同义同默认 |
| `.setModelDir(Path)` | `model/bge-small-zh-v1.5` | 编码器目录 |
| `.setM(int)` / `.setEfConstruction(int)` | `16` / `100` | HNSW 建图参数 |
| `.setStoredFields(List<String>)` | 空 | 要一并存原文的字段；不配则检索**回不了字段值** |
| `.setWeights(Map<String,Float>)` | 全 `1.0` | 缺省字段按 1.0，最终归一化 |
| `.setDevice(Kind)` / `.setGpuDevice(int)` / `.setCudaLibDir(Path)` | `AUTO` / `0` / `null` | 见 §4.8 |

两条入口：`addDoc(Document)`（主路径，走 ONNX 编码）与 `addVector(field, uniqueId, float[])`（喂**预计算**向量，不加载模型 —— 给"向量已由别处算好"以及无需模型的测试用）。

**内存**：编码结果全部留在堆里，直到 `commit()` 才建图（jvector 的 `GraphIndexBuilder` 是批量接口）。峰值约 **字段数 × 文档数 × 维度 × 4 字节** —— 10 万 × 512 × 3 字段约 615 MB。

> 跳过的文档会**计数**而非静默丢弃：没主键整条跳过（`droppedNoKey`），配置的字段一个都没有也整条跳过（`droppedNoField`）；只有部分字段就收下有的那几个，缺的那个字段的图里没有这条文档。

### 4.5 检索：`VectorSearcher`

```java
try (var s = VectorSearcher.open(Path.of("index-data/goods-vec"))) {
    for (VectorHit h : s.search("老黄冰糖", 10)) {       // 文本查询，内部编码一次
        System.out.println(h.uniqueId() + "\t" + h.score());
    }
    Document d = s.docByPk(49732L);                     // 按业务主键取原文
}
```

- `search(String, int)` 走编码器；批量检索请复用 `search(float[], int[, rerankK])`（**所有字段共用同一个查询向量**）。
- 每个字段取回的候选数是 `max(topK × 4, 64)` 而不是 `topK`：某个文档在 A 字段排第 11、在 B 字段排第 1，若每张图只取 10 条，它在 A 里的贡献就丢了。
- `open(indexDir, modelDir, cacheVectors)`：`modelDir` 传 `null` 表示用 `meta.json` 里记的那个（**换机部署时应当显式传**）；`cacheVectors=false` 让图**磁盘驻留** —— 字段一多就该关掉（3 个字段载入堆是 600 MB 量级，磁盘驻留每张图只要约 5 MB）。
- **打开时校验模型**：`dim` 与 `modelFingerprint` 必须与当前 `TextEncoder` 一致，否则**启动即失败**。换模型必须重建索引。
- **磁盘格式版本**：`meta.formatVersion` 低于当前值（旧格式，没有存储字段这一层）**直接拒绝、要求重建**，与文本索引对 BKD 的做法一致 —— 宁可要求重建，也不留"半张索引有 fields、半张没有"的半吊子状态。

### 4.6 模型文件格式（`model.json`）

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

### 4.7 存储字段（原文）

**向量索引默认不存原文。** `commit()` 只写 `graph.idx` / `pk.map` / `meta.json` —— 编码完文本就丢。所以默认情况下检索能返回 pk 和分数，**回不了字段值**。

配了 `setStoredFields([...])` 之后原文会另存一份。格式参考文本索引的 fdt 那一套，但在 `vector` 模块内自己实现（本模块只依赖 `core`，拿不到 `segment` / `recall` 的代码）：

```
stored.fdt   LZ4 压缩块，顺序拼接
stored.fdx   [VLong 块首 pk][VLong fdt 偏移][长度]，每块一条
stored.fdm   [Int 名长][字段名][1 字节类型位]，按字段名排序
```

几个要点：

- **键用 pk，不是 docID。** 向量索引是每字段独立的 ordinal 空间，pk 才是唯一跨字段稳定的标识。
- **块大小 16 KiB；超块翻倍扩容。** 单篇文档的存储字段超过一个块时（存几千字的商品详情就会），把块扩容一次、每次翻倍 —— 不丢数据，也不静默截断。（文本索引那边遇到同样情况是**静默丢掉**该文档的存储字段，这里没照抄。）
- `docByPk(pk)` 的返回有价值的三态：**`null`** = 这个 pk 不在这份索引里；**空 `Document`** = 在，但这份索引没存它的字段 —— 两者得分得清。
- 响应里带 `storedFields: [...]` 如实报出这份索引存了哪些字段。

### 4.8 模型编码设备（GPU / CPU）

编码器默认 **`AUTO`：有 GPU 用 GPU，没有回退 CPU**（`OnnxDevice.Kind`）。

- **探测只能靠"真试一次"**：`OrtEnvironment.getAvailableProviders()` 返回的是"制品里编译进了什么"，不是"这台机器能不能跑" —— GPU 版制品在没装 CUDA 的机器上照样把 CUDA 列进去。唯一可靠的做法是拿一份**临时** `SessionOptions` 试 `addCUDA(0)`、接异常（探测失败可能已在原生侧留下半注册状态，所以不拿真 opts 去试）。
- **`Kind.GPU` 会拒绝而非静默降级**：显式要 GPU 却拿不到时直接抛异常 —— 静默降级是最难查的错。
- **GPU 版制品不打包 CUDA 运行时**：宿主机需自带 CUDA 12（`libcudart.so.12` 等）。不便在宿主机安装时，可用 `setCudaLibDir` 让 `OnnxDevice` 预加载（免设 `LD_LIBRARY_PATH`）。
- 判断"是否真在 GPU 上"以 `TextEncoder.device().onGpu()` 为准，而不是看请求了什么。

> **编码是整条链路的瓶颈**：CPU 编码实测只有约 **202 doc/s**（10 万条单字段约 8 分钟），GPU（RTX 3060）约 5,900 doc/s。编码会按 `model.json` 的 `encodeBatchSize` **分块**——ONNX Runtime 的中间激活值随 batch 线性增长（约 47 KB/token），不分块会把原生内存顶爆、连 `-Xmx` 都管不住。

### 4.9 索引实现与依赖

`JVectorGraph` 基于 **jvector 4.0.1**（纯 Java、无 JNI，HNSW + 磁盘驻留 + PQ/BQ 量化）。默认参数：`M=16`、`efConstruction=100`、`rerankK=256`（检索时的候选池，`VectorSearcher.setRerankK` 可调）、相似度函数为**余弦**。

关键依赖：

| 依赖 | 用途 |
|---|---|
| `com.hawk:core` | 输入模型（`Document` / `Field`）与主键映射（`PkMapStore`），与 `indexer` 共用同一套 |
| `com.microsoft.onnxruntime:onnxruntime` | 推理，默认 **CPU** 版（任何机器都能跑）。`-Pgpu` 换成 `onnxruntime_gpu`（约 670 MB） |
| `ai.djl.huggingface:tokenizers` | 分词，JNI 封装 Rust `tokenizers`，直接读 `tokenizer.json`（WordPiece / BPE / Unigram） |
| `io.github.jbellis:jvector` | ANN 索引 |
| `com.alibaba:fastjson` | 读 `model.json` / `meta.json` |
| `ch.qos.logback:logback-classic` | 日志（**不引 log4j**，见 `vector/pom.xml` 注释） |

### 4.10 当前状态

- 模块可端到端跑通（经 `cloud-*` 链路验证），主键与倒排链路共享同一套（`goods.csv` 第一列）。
- **本模块无单测、无独立 demo**，验证走 `cloud-indexer` / `cloud-recall`。
- `util/NpyReader` 保留但**无调用方**（旧的 `.npy` 商品向量路线已作废，见 §4.1）。
- 磁盘格式变更（`meta.formatVersion`）会让旧索引被读侧拒绝 —— **改格式就要重建索引**，不做兼容分支。
- 只做**一次性批量**建图：要支持增量（边收边建图）才能把峰值内存降到与文档数无关。
- 存储优化杠杆是**量化**（jvector 的 `FusedPQ` / `NVQ`），当前未启用 —— `save()` 只写全精度向量。实测全精度下约 **2,124 字节/文档/字段**。
- `model/` 目录不在版本控制内，打包在根目录的 `model.tar.gz`（见 §2）。

---

## 5. 目录结构

```
Hawk/
├── core/               Directory、文件格式、编解码、BKD、字段模型
├── segment/            中文分词（N-最短路径）与词典资源
├── indexer/            IndexWriter、DocWriter、IndexMerger
├── recall/             DirectoryReader、Searcher、Query、Similarity
├── demo/               使用示例（BuildIndex、RecallDemo）
├── vector/             向量索引 SDK（详见 §4）
│   └── src/main/java/hawk/vector/
│       ├── writer/     VectorIndexWriter、VectorIndexConfig
│       ├── search/     VectorSearcher、VectorHit
│       ├── graph/      JVectorGraph（jvector 封装）
│       ├── meta/       VectorIndexMeta（meta.json）
│       ├── model/      ModelDescriptor、TextEncoder、OnnxDevice
│       ├── stored/     StoredFields / Writer / Reader（原文存储）
│       └── util/       NpyReader（保留但当前无调用方）
├── cloud-indexer/      分布式索引器（详见 §1.7）
├── cloud-recall/       分布式召回
├── cloud-coordinator/  协调器
├── benchmark/          JMH 基准（Hawk vs Lucene）
│   ├── scripts/        run-hawk-benchmark.sh / run-lucene-benchmark.sh
│   └── results/        历史基准结果
├── goods.csv           基准语料（10 万条商品标题）
├── model/              模型权重与商品向量（大文件不进 git，见 §2）
├── model.tar.gz        上面那个目录的打包（不进 git）
├── index-data/         倒排索引（派生，已 gitignore）
├── INDEX_FORMAT.md     索引文件格式说明
└── README.md
```

---