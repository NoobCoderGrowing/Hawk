# Hawk 索引文件格式说明

本文档描述 Hawk 搜索引擎在磁盘上的索引目录结构、各文件的存储内容，以及二进制编码格式。实现代码主要分布在 `core/`（目录与编码）、`indexer/`（写入与合并）、`recall/`（读取与检索）模块。

---

## 1. 索引目录概览

一个索引对应一个目录，例如 `/opt/index/1/`。典型内容如下：

```
/opt/index/1/
├── segment.info      # 段元数据（全索引级）
├── pk.map            # 主键 uniqueID → 全局 docID 映射
├── deleted.ids       # 已删除文档的主键集合（可选）
├── 1.fdt             # 段 1：存储字段数据（Field Data Table）
├── 1.fdx             # 段 1：存储字段索引（docID → .fdt 偏移）
├── 1.fdm             # 段 1：字段元数据（BM25 等统计）
├── 1.tim             # 段 1：词项字典（field + term → .frq 偏移）
├── 1.frq             # 段 1：倒排 posting 列表
└── 1.bkd             # 段 1：数值字段 BKD 树
```

索引过程中可能短暂出现 `2.*`（第二个段）和 `3.*`（合并中间产物）。**合并完成后只保留 `1.*`，搜索端也只读取 `1.*`。**

| 文件 | 作用 |
|------|------|
| `segment.info` | 记录建索日期、段数量、最大 docID、格式版本 |
| `N.fdt` | 文档的 Stored 字段，按 LZ4 压缩块存储 |
| `N.fdx` | 压缩块起始 docID 及其在 `.fdt` 中的字节偏移 |
| `N.fdm` | 每个字段的类型标志、总字段长度、文档数（用于 BM25 平均长度） |
| `N.tim` | 倒排词典：`(字段名, 词项)` → `.frq` 中的 posting 偏移 |
| `N.frq` | 每个词项的 posting 列表：docID、词频、字段长度 |
| `N.bkd` | `DoubleField` 数值点的 BKD 树，支持范围查询 |
| `pk.map` | 业务主键 `uniqueID` 与全局 `docID` 的双向映射基础 |
| `deleted.ids` | 软删除标记，posting 仍保留，检索时过滤 |

**不在磁盘持久化：** 搜索打开索引时，会从 `1.tim` 在内存中构建 Term FST（`MMapDirectoryReader.constructTermFST`），用于快速定位词项。

---

## 2. 基础编码

定义于 `util/DataOutput.java`、`util/DataInput.java`、`util/NumberUtil.java`。

| 类型 | 编码 | 说明 |
|------|------|------|
| **VInt** | 变长整数，每字节低 7 位为数据，最高位 `1` 表示还有后续字节 | 仅支持非负数，1～5 字节 |
| **VLong** | 同 VInt 规则，最多 10 字节 | 仅支持非负数 |
| **Int** | 4 字节大端（big-endian） | |
| **Long** | 8 字节大端 | |
| **Byte** | 1 字节原始值 | |
| **UTF-8 文本** | 原始字节，长度由前置 Int 或 VInt 指定 | |

**压缩：** `.fdt` 中的数据块默认使用 **LZ4** 压缩，未压缩块大小由 `IndexFormatConfig.blocSize` 控制（默认 **16 KiB**）。

---

## 3. segment.info（20 字节）

源文件：`directory/SegmentInfo.java`

| 偏移 | 大小 | 字段 | 编码 |
|------|------|------|------|
| 0 | 8 | `timeStamp` | UTF-8 日期字符串 `yyyyMMdd` |
| 8 | 4 | `segCount` | Int BE，当前活跃段数量 |
| 12 | 4 | `preMaxID` | Int BE，已分配的最大全局 docID |
| 16 | 4 | `formatVersion` | Int BE，索引格式版本 |

**formatVersion 取值：**

| 值 | 含义 |
|----|------|
| `0` | 旧版：数值字段通过前缀 term 写入倒排索引 |
| `1` | 当前版：数值字段使用 BKD 树（`BkdFormatVersion.CURRENT`） |

若文件不足 20 字节，读取时默认 `formatVersion = 0`。搜索端要求 `formatVersion >= 1` 才支持 BKD 数值查询。

**更新时机：** 每次 `DocWriter.flush()` 结束时调用 `directory.updateSegInfo(lastDocID, +1)`；合并完成后调用 `updateSegInfo(..., -1)` 将 `segCount` 减 1。

---

## 4. 段文件命名与 docID 模型

源文件：`directory/FSDirectory.java`

新段编号规则：

```
curSeg = segmentInfo.segCount + 1
```

| 事件 | segCount 变化 | 生成的文件 |
|------|---------------|------------|
| 首次 flush | 0 → 1 | `1.fdt` … `1.bkd` |
| 内存再次刷盘 | 1 → 2 | `2.fdt` … `2.bkd` |
| 段合并完成 | 2 → 1 | 仅保留 `1.*` |

**docID 分配：**

- 段内局部 ID：从 `docIDAllocator` 递增分配（内部从 0 开始，首文档为 1）。
- 全局 docID：`localDocID + docBase`，其中 `docBase = segmentInfo.preMaxID`（flush 时确定）。
- `preMaxID` 表示当前索引中最大的全局 docID，也是 `DirectoryReader.getTotalDoc()` 的返回值。

---

## 5. N.fdt — 存储字段数据表

**写入：** `DocWriter.flushStored()` → `insertBlock()` → `writeCompressedBloc()`

磁盘上是多个 **LZ4 压缩块** 顺序拼接。每个**未压缩块**内部结构：

```
[块内可重复，直到 16KiB 缓冲区满]
  VInt   docID           # 全局 docID（localID + docBase）
  VInt   fieldCount      # 本块内该文档的 stored 字段数
  对每个 stored 字段：
    bytes  field.customSerialize()   # 见 §10
```

当缓冲区放不下下一个文档时，将当前块压缩写入 `.fdt`，并在 `.fdx` 中记录该块的起始 docID 与文件偏移，然后开启新块。

**读取：** `Searcher.doc()` 通过 `.fdx` 定位压缩块 → LZ4 解压 → 顺序扫描块内文档直到命中目标 docID。

---

## 6. N.fdx — 存储字段索引

**写入：** `DocWriter.writeFDX()`

每条记录顺序存储：

```
VInt   blockStartDocID    # 该压缩块中第一个文档的全局 docID
VLong  fdtFileOffset      # 对应块在 .fdt 中的字节偏移
```

**读取：** `MMapDirectoryReader.constructFdxMap()` 将全部条目加载到 `TreeMap<Integer, byte[]>`，key 为 `blockStartDocID`，value 为 VLong 偏移的原始字节。

**文档定位：** 对目标 docID 做 `floorKey(docID)`，得到所在压缩块的起始偏移；下一条 fdx 记录（或文件末尾）作为块结束边界。

**合并：** `IndexMerger.mergeFDX()` 将 segment 2 的 fdx 条目追加到 segment 1 的 fdx 末尾，并将 fdt 偏移加上 segment 1 的 `.fdt` 文件大小。

---

## 7. N.fdm — 字段元数据

**写入：** `DocWriter.writeFDM()`，按字段名字典序排序。

每条记录：

```
Int    fieldNameLength
bytes  fieldName          # UTF-8
Byte   fieldType          # 位标志，见 §11
Int    fieldLengthSum     # 该字段在所有文档中的长度总和
Int    docCount            # 包含该字段的文档数
```

**用途：** 搜索时计算 BM25 所需的平均字段长度：`avgFieldLength = fieldLengthSum / docCount`。

**合并：** 按字段名归并，相同字段的 `fieldLengthSum` 与 `docCount` 分别相加。

---

## 8. N.tim — 词项字典

**写入：** `DocWriter.writeTIM()`，按 `(字段名, 词项)` 字典序排序。

每条记录：

```
Int    fieldNameLength
bytes  fieldName          # UTF-8
Int    termLength
bytes  term               # UTF-8 词项字节
VLong  frqFileOffset      # 该词项 posting 列表在 .frq 中的字节偏移
```

**读取：** 仅 **StringField**（类型位 `0b00001000`）的条目会加入内存 Term FST。`DoubleField` 的数值索引只存在于 `.bkd`，不写入 tim/frq。

---

## 9. N.frq — 倒排 Posting 列表

**写入：** `DocWriter.writeFRQ()`

每个词项对应一段 posting 数据：

```
VInt   docCount           # posting 数量
重复 docCount 次：
  VInt   docID             # 全局 docID
  VInt   termFrequency     # 该文档内该词项出现次数
  VInt   fieldLength       # 原始字段值长度（BM25 归一化用）
```

同一词项的 posting 按 docID 升序排列（flush 前在内存中按文档顺序组装）。

**合并：** 相同 `(field, term)` 的 posting 列表首尾拼接（docID 保持全局唯一，无需重编号）。

---

## 10. Stored 字段序列化格式

各字段类型通过 `Field.customSerialize()` 序列化，直接嵌入 `.fdt` 块中。

### StringField

```
VInt   nameLength
bytes  name               # UTF-8
VInt   valueLength
bytes  value              # UTF-8
```

### DoubleField / PrimaryKeyField

```
VInt   nameLength
bytes  name               # UTF-8
Byte   valueLength = 0x08 # 固定单字节，读取时按 VInt 解析为 8
Long   value              # 8 字节大端
                         # DoubleField: Double.doubleToLongBits(value)
                         # PrimaryKeyField: uniqueID 的 long 值
```

**注意：** `PrimaryKeyField` 固定字段名为 `"uniqueID"`，必须存在于每个文档中，用于 `pk.map` 维护。

---

## 11. 字段类型标志位

定义于 `DocWriter.getFieldType()`：

| 位 | 掩码 | 含义 |
|----|------|------|
| 0 | `0x01` | Stored（可存储、可检索原文） |
| 1 | `0x02` | Tokenized（参与分词索引） |
| 2 | `0x04` | DoubleField |
| 3 | `0x08` | StringField |
| 4 | `0x10` | PrimaryKeyField |

---

## 12. N.bkd — 数值字段 BKD 树

**写入：** `BkdFileWriter.write()`  
**读取：** `BkdFileReader.open()` → 每字段一个 `BkdReader`

仅索引 `Tokenized.YES` 的 `DoubleField`。文档处理时将 double 转为可排序 long（`NumberUtil.double2SortableLong`），再写入 BKD 点 `(sortableValue, docID)`。

### 文件头（16 字节 + 树体 + 目录）

```
Int    magic           = 0x424B4431  ("BKD1")
Int    formatVersion   = 1
Int    fieldCount      # 有数值点的字段数
Long   directoryOffset # 写完后回填，指向字段目录起始位置
```

### 字段目录（写在文件末尾，按字段名字典序）

每个字段：

```
Int    fieldNameLength
bytes  fieldName       # UTF-8
Int    numPoints        # 该字段点数
Long   rootOffset       # 根节点在文件中的字节偏移
```

### 内部节点（nodeType = 0）

```
Byte   nodeType = 0
Long   splitValue        # BE
Long   minValue          # BE
Long   maxValue          # BE
Long   leftChildOffset   # BE，写完后回填
Long   rightChildOffset  # BE，写完后回填
```

### 叶子节点（nodeType = 1）

```
Byte   nodeType = 1
Long   minValue          # BE
Long   maxValue          # BE
Int    pointCount        # BE
重复 pointCount 次：
  Long   sortableValue   # BE
  VInt   docID           # 全局 docID
```

叶子节点最多容纳 `bkdMaxPointsInLeaf` 个点（默认 **512**，见 `IndexFormatConfig`）。点按 `(sortableValue, docId)` 无符号升序排列。

---

## 13. pk.map — 主键映射

源文件：`directory/PkMapStore.java`

```
Int    entryCount
重复 entryCount 次（按 uniqueID 升序）：
  Long   uniqueID        # 业务主键
  Int    docID            # 全局 docID
```

- `IndexWriter` 打开时加载，每次 `commit()` 时全量写回。
- 每篇文档必须通过 `PrimaryKeyField` 注册主键。

---

## 14. deleted.ids — 软删除标记

源文件：`directory/DeletedIdsStore.java`

```
Int    count
重复 count 次（按 uniqueID 升序）：
  Long   uniqueID
```

- 删除操作不修改倒排或 stored 数据，仅在检索时通过 `pk.map` 反查 docID 后过滤。
- `DirectoryReader.isLive(docID)` 和 `numDocs()` 会参考此文件。

---

## 15. 索引写入流程

源文件：`indexer/writer/DocWriter.java`、`IndexWriter.java`

```
addDoc(Document)
  ├─ processStoredFields()     → 内存 fdt 列表（stored 字段字节池）
  ├─ processIndexedFields()
  │    ├─ StringField (tokenized)  → 内存 ivt（field+term → posting）
  │    ├─ DoubleField (tokenized)  → 内存 bkdFields（sortableValue + docID）
  │    └─ stored-only 字段         → 仅登记 fdm 元数据
  └─ registerPrimaryKey()      → 内存 pkMap

触发 flush（RAM ≥ 95% maxRamUsage 或 commit()）：
  1. 排序 fdt、fdm、ivt
  2. flushStored  → .fdt + .fdx
  3. flushIndexed → .fdm + .tim + .frq
  4. flushBkd     → .bkd
  5. updateSegInfo(lastDocID, segCount + 1)
  6. mergetest()  若 segCount > 1 且 enableMerge=true，执行段合并
```

`IndexWriter.commit()` 等待所有索引线程完成后执行最终 flush，并调用 `PkMapStore.save()` 持久化主键映射。

---

## 16. 段合并（IndexMerger）

源文件：`indexer/writer/IndexMerger.java`

**触发条件：** `IndexConfig.enableMerge == true`（默认开启）且 flush 后 `segCount > 1`。

**前置校验：** 目录中 segment 文件数 = `segCount × 6 + 1`（6 种段文件 + `segment.info`）。

| 组件 | 合并策略 | 结果 |
|------|----------|------|
| **fdt / fdx** | 将 segment 2 的 `.fdt` 各压缩块追加到 segment 1 末尾；segment 2 的 fdx 偏移整体平移 | 原地写入 `1.fdt` / `1.fdx` |
| **fdm** | 按字段名归并，累加统计量 | 写入 `3.fdm` → 重命名为 `1.fdm` |
| **tim / frq** | 按 `(field, term)` 归并；相同词项拼接 posting | 写入 `3.tim` / `3.frq` → 重命名为 `1.*` |
| **bkd** | 按字段名读取两段点集，归并排序 | 写入 `3.bkd` → 重命名为 `1.bkd` |

**清理：** 删除所有 `2.*`；对每个 `3.{tim,frq,fdm,bkd}` 删除旧 `1.*` 并将 `3.*` 移动为 `1.*`。

合并后：`updateSegInfo(docIDAllocator + docBase, -1)`，`segCount` 减 1，`preMaxID` 不变。

---

## 17. 搜索时数据流

源文件：`recall/reader/MMapDirectoryReader.java`、`recall/search/Searcher.java`

| 查询类型 | 使用的索引文件 |
|----------|----------------|
| TermQuery / StringQuery | 内存 Term FST → `1.tim` 偏移 → `1.frq` posting → BM25 打分 |
| NumericRangeQuery | `1.bkd` 树范围相交 → docID 集合 |
| 取文档原文 `doc()` | `1.fdx` 定位块 → `1.fdt` LZ4 解压 → 反序列化 stored 字段 |
| 过滤已删除文档 | `pk.map` + `deleted.ids` |

---

## 18. 架构关系图

```
                    ┌─────────────────────────────────────┐
                    │           索引目录 /opt/index/1      │
                    │  segment.info  pk.map  deleted.ids  │
                    │  ┌───────────────────────────────┐  │
                    │  │  Segment 1（合并后唯一可读段）  │  │
                    │  │  1.fdt  1.fdx  1.fdm           │  │
                    │  │  1.tim  1.frq  1.bkd           │  │
                    │  └───────────────────────────────┘  │
                    └─────────────────────────────────────┘
                           ▲                    │
              IndexWriter / DocWriter           │ MMapDirectoryReader
              IndexMerger（合并）                ▼
                                    Searcher（FST + BM25 + BKD）
```

---

## 19. 限制与注意事项

1. **4 GiB 上限：** `.fdt`、`.fdx`、`.frq`、`.fdm` 通过 `int` 强转文件大小，单文件不得超过 4 GB。
2. **搜索只读 `1.*`：** 若 `enableMerge=false` 导致存在 `2.*` 段，未合并的数据对搜索不可见。
3. **Term FST 不落盘：** 每次打开 `DirectoryReader` 都从 `1.tim` 重建，大索引打开会有额外开销。
4. **docID 从 1 开始：** `docIDAllocator` 先自增再赋值，首篇文档全局 ID = `docBase + 1`。
5. **Stored 字段顺序：** `fieldMap` 中 stored 字段应位于非 stored 字段之前，以保证 `insertBlock` 连续写入时字段顺序正确。
6. **数值索引：** 当前格式下 `DoubleField` 只写 BKD，不写 tim/frq；`Tokenized.NO` 的数值字段仅作为 stored 字段存储。

---

## 20. 关键源码索引

| 主题 | 路径 |
|------|------|
| Flush 与写盘格式 | `indexer/src/main/java/hawk/indexer/writer/DocWriter.java` |
| 段合并 | `indexer/src/main/java/hawk/indexer/writer/IndexMerger.java` |
| 段元数据 | `core/src/main/java/directory/SegmentInfo.java` |
| 段文件命名 | `core/src/main/java/directory/FSDirectory.java` |
| 搜索读取 | `recall/src/main/java/hawk/recall/reader/MMapDirectoryReader.java` |
| 文档检索 | `recall/src/main/java/hawk/recall/search/Searcher.java` |
| BKD 读写 | `core/src/main/java/util/bkd/BkdFileWriter.java`、`BkdFileReader.java` |
| 主键 / 删除 | `core/src/main/java/directory/PkMapStore.java`、`DeletedIdsStore.java` |
| 编解码 | `core/src/main/java/util/DataOutput.java`、`DataInput.java` |
| 格式配置 | `core/src/main/java/common/IndexFormatConfig.java` |
