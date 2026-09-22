#!/usr/bin/env bash
#
# 一条命令把 web 演示跑起来。
#
#   ./runDemo.sh
#
# 依次做四件事，缺什么补什么：
#   1. 检查环境（JDK 22+）
#   2. 编译全部模块
#   3. 检查 index-data/ 下的**全文索引**，缺失或不全就用 goods.csv 重建（纯 Java，约 5 秒，不需要网络）
#   4. 检查 model/ 下的**商品向量**与**模型权重**，缺失或不全就从 GitHub Release 下载
#   5. 启动 web 模块（前台运行，Ctrl-C 退出）
#
# 为什么大文件不进 git：
#   model/bge-small-zh-v1.5/encoder.onnx      91 MB   ← 唯一无法从仓库内容再生的
#   model/zero_shot_bge_small/corpus_emb.npy  98 MB   ← 有模型就能重建（18 秒）
#   index-data/goods/                         21 MB   ← goods.csv 就能重建（5 秒）
#   goods.csv 是源码、正常跟踪；model/ 整个目录不进 git（见 .gitignore）
#
# 环境变量：
#   JAVA_HOME          JDK 22+ 的位置，优先于 PATH 上的 java
#   HAWK_ASSETS_URL    资产包地址，换托管位置时覆盖
#   HAWK_CORPUS        语料文件，默认 goods.csv
#   HAWK_INDEX_DIR     全文索引目录，默认 index-data/goods
#   HAWK_PORT          监听端口，默认 8080
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

ASSETS_URL="${HAWK_ASSETS_URL:-https://github.com/NoobCoderGrowing/Hawk/releases/download/demoDependency/model.tar.gz}"
CORPUS="${HAWK_CORPUS:-goods.csv}"
INDEX_DIR="${HAWK_INDEX_DIR:-index-data/goods}"
PORT="${HAWK_PORT:-8080}"

MODEL_DIR="model/bge-small-zh-v1.5"
VECTOR_DIR="model/zero_shot_bge_small"

# 全文索引必须具备的文件（.bkd 只在有数值字段时才有，不要求）
INDEX_FILES=(segment.info pk.map 1.fdt 1.fdx 1.fdm 1.tim 1.frq)
# 模型权重 + 商品向量
MODEL_FILES=("$MODEL_DIR/encoder.onnx" "$MODEL_DIR/model.json" "$MODEL_DIR/tokenizer.json")
VECTOR_FILES=("$VECTOR_DIR/corpus_emb.npy" "$VECTOR_DIR/corpus_emb_ids.npy")

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
die()  { printf '\n\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 1. 环境

say "检查环境"

# 优先用 JAVA_HOME —— 很多机器上 PATH 里的 java 是旧版本，而 JDK 25 装在别处
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
else
  JAVA_BIN="$(command -v java || true)"
  # 从 PATH 推断时解掉软链，'上两级目录'才真的是 JDK 根
  if [ -n "$JAVA_BIN" ]; then
    resolved="$(readlink -f "$JAVA_BIN" 2>/dev/null || echo "$JAVA_BIN")"
    guess="$(dirname "$(dirname "$resolved")")"
    if [ -x "$guess/bin/javac" ]; then
      export JAVA_HOME="$guess"
    else
      warn "推断不出 JAVA_HOME，交给 Maven 用 PATH 上的 java"
    fi
  fi
fi
[ -n "$JAVA_BIN" ] || die "找不到 java。本项目需要 JDK 22+（用了 java.lang.foreign）。"

JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 22 ] 2>/dev/null; then
  die "需要 JDK 22+，当前是 ${JAVA_MAJOR:-未知}（$JAVA_BIN）。
    索引文件用 MemorySegment 以 long 寻址来突破 2 GiB 单文件上限，FFM 在 JDK 22 才转正。

    指定一个 JDK 22+ 再跑：
      JAVA_HOME=/path/to/jdk-25 ./runDemo.sh"
fi
ok "JDK $JAVA_MAJOR  ($JAVA_BIN)"

command -v mvn >/dev/null || die "找不到 mvn。"
ok "Maven"

[ -f "$CORPUS" ] || die "找不到语料 $CORPUS —— 它是索引与向量的共同来源，必须存在。"
ok "语料 $CORPUS"

# ---------------------------------------------------------------- 2. 构建

say "构建"
mvn -q -DskipTests package
ok "全部模块编译完成"

# ---------------------------------------------------------------- 3. 全文索引

say "全文索引"
missing=()
for f in "${INDEX_FILES[@]}"; do
  [ -f "$INDEX_DIR/$f" ] || missing+=("$f")
done

if [ ${#missing[@]} -eq 0 ]; then
  ok "$INDEX_DIR 已就绪（${#INDEX_FILES[@]} 个文件齐全）"
else
  if [ -d "$INDEX_DIR" ] && [ ${#missing[@]} -lt ${#INDEX_FILES[@]} ]; then
    warn "$INDEX_DIR 不完整，缺 ${missing[*]} —— 清掉重建"
  else
    warn "$INDEX_DIR 不存在 —— 用 $CORPUS 重建"
  fi
  "$JAVA_BIN" -cp "demo/target/classes:demo/target/dependency/*" \
              demo.BuildIndex "$CORPUS" "$INDEX_DIR"
  ok "$INDEX_DIR"
fi

# ---------------------------------------------------------------- 4. 模型与向量

say "模型权重与商品向量"
need_assets=()
for f in "${MODEL_FILES[@]}" "${VECTOR_FILES[@]}"; do
  [ -f "$f" ] || need_assets+=("$f")
done

if [ ${#need_assets[@]} -eq 0 ]; then
  ok "$MODEL_DIR 与 $VECTOR_DIR 均已就绪"
else
  for f in "${need_assets[@]}"; do warn "缺 $f"; done
  warn "从 Release 下载：$ASSETS_URL"

  if ! command -v curl >/dev/null && ! command -v wget >/dev/null; then
    die "既没有 curl 也没有 wget，无法下载。"
  fi

  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT

  fetched=0
  if command -v curl >/dev/null; then
    curl -fL --progress-bar -o "$tmp/assets.tar.gz" "$ASSETS_URL" && fetched=1 || true
  else
    wget -q --show-progress -O "$tmp/assets.tar.gz" "$ASSETS_URL" && fetched=1 || true
  fi

  if [ "$fetched" = "1" ]; then
    # 资产包里带 model/ 顶层前缀（打包命令是 `tar -czf model.tar.gz model/`），
    # 所以解压到仓库根，而不是 -C model 里 —— 后者会解成 model/model/…
    tar -xzf "$tmp/assets.tar.gz" -C "$ROOT"
    ok "已解压到 model/"

    still=()
    for f in "${MODEL_FILES[@]}" "${VECTOR_FILES[@]}"; do
      [ -f "$f" ] || still+=("$f")
    done
    [ ${#still[@]} -eq 0 ] || die "解压后仍缺：${still[*]}
    资产包的结构应为 bge-small-zh-v1.5/ 与 zero_shot_bge_small/ 两个目录（见 README §8.3）。"
  else
    # 这不是错误 —— 自建是完全可行的路径，只是慢一些。
    cat <<EOF

  下载失败（多半是这个 Release 还没建，见 README §8）。

  可以自己生成，两个选择：

  A. 只重建商品向量（有模型就行，约 18 秒 / GPU）
       cd ../Hawk-Vector
       ./scripts/encode.sh configs/zero_shot_bge_small.yaml \\
           --corpus "$ROOT/$CORPUS" \\
           --out    "$ROOT/$VECTOR_DIR/corpus_emb.npy"

  B. 连模型也从零导出（需要 Python + torch，见 Hawk-Vector 的 README）
       在 Hawk-Vector 里跑 export_onnx 得到 model.json / encoder.onnx / tokenizer.json，
       放进 $ROOT/$MODEL_DIR/

EOF
    exit 1
  fi
fi

# ---------------------------------------------------------------- 5. 前端产物

say "前端产物"
if [ -f web/src/main/resources/static/index.html ]; then
  ok "已构建"
elif command -v npm >/dev/null; then
  warn "未构建，跑一次 Vite"
  ( cd web/frontend && npm install --no-audit --no-fund && npm run build )
  mvn -q -pl web -am -DskipTests package
  ok "已构建"
else
  warn "未构建，且找不到 npm —— 页面会 404。装好 Node 后跑："
  warn "  cd web/frontend && npm install && npm run build && mvn -pl web -am -DskipTests package"
fi

# ---------------------------------------------------------------- 6. 启动

say "启动 web 演示"
cat <<EOF
  http://localhost:$PORT
  倒排索引  $INDEX_DIR
  向量      $VECTOR_DIR
  模型      $MODEL_DIR

  Ctrl-C 停止。启动约 20 秒（建 HNSW 索引 + 加载 ONNX 模型）。
EOF

exec "$JAVA_BIN" -Xmx3g \
  -Dserver.port="$PORT" \
  -Dhawk.web.index-dir="$INDEX_DIR" \
  -Dhawk.web.vector-dir="$VECTOR_DIR" \
  -Dhawk.web.model-dir="$MODEL_DIR" \
  -cp "web/target/classes:web/target/dependency/*" \
  hawk.web.WebApplication
