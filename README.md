# Hawk

Hawk 是一个**垂直搜索引擎**的检索内核实现：写入端（indexer）与召回端（recall）解耦，支持中文分词、倒排索引、BM25 打分、数值范围查询与主键级别的软删除。

本仓库聚焦**倒排索引**这条链路。

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
├── segment     中文分词：N-最短路径分词、词图、标点与词典资源
├── indexer     写入层：IndexWriter / DocWriter / IndexMerger
├── recall      召回层：DirectoryReader / Searcher / Query / Similarity
└── demo        示例：写索引、按词/串/数值范围检索、删除文档
```

依赖关系：

```
              recall  indexer
                 │    ╱   │
                 │  ╱     │
              segment   core
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

触发 flush（RAM 使用量 ≥ 95% maxRamUsage，或 commit()）：
  1. 排序 fdt / fdm / ivt
  2. flushStored  → {n}.fdt + {n}.fdx
  3. flushIndexed → {n}.fdm + {n}.tim + {n}.frq
  4. flushBkd     → {n}.bkd
  5. updateSegInfo(+1 段)
  6. 若 segCount > 1 且 enableMerge → IndexMerger 合并
```

`IndexWriter.commit()` 等待所有写入线程结束，做最后一次 flush，并持久化 `pk.map`。

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

要求：JDK 8+（基准测试在 JDK 17 上运行），Maven。

```bash
# 全量构建
mvn -DskipTests package
```

示例类一览（`demo/src/main/java/demo`）：`demo` 模块中的 `main` 方法直接指向本地索引目录，按需修改路径后运行。

| 类 | 用途 |
|----|------|
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
3. **规模有限。** 索引基准最大 10k 篇、检索基准 50k 篇，未覆盖百万级。单文件 2 GiB 上限已随 FFM 迁移解除（见 [§4](#4-已知限制)），但**文档数 ~2^31 的上限未动**（posting 的 `VInt docID`、`preMaxID`、`pk.map` docID 仍是 `int`），到该量级需升 `formatVersion`。
4. **字段类型不完全等价。** Lucene 侧 `descript`/`digt` 以 `StoredField` 写入，Hawk 侧为 `Tokenized.NO` 的存储字段；`uniqueID` 在 Lucene 侧同时写入 `LongPoint` 与 `StoredField`（含未被查询使用的点索引）。
5. **单机单 JVM。** 未涉及跨机部署，与本项目"写入端/召回端可分离"的架构定位不完全对应。

---

## 4. 已知限制

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

## 5. 目录结构

```
Hawk/
├── core/           Directory、文件格式、编解码、BKD、字段模型
├── segment/        中文分词（N-最短路径）与词典资源
├── indexer/        IndexWriter、DocWriter、IndexMerger
├── recall/         DirectoryReader、Searcher、Query、Similarity
├── demo/           使用示例
├── benchmark/      JMH 基准（Hawk vs Lucene）
│   ├── scripts/    run-hawk-benchmark.sh / run-lucene-benchmark.sh
│   └── results/    历史基准结果
├── goods.csv       基准语料（10 万条商品标题）
├── INDEX_FORMAT.md 索引文件格式说明
└── README.md
```
