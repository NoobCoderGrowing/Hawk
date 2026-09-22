#!/usr/bin/env bash
#
# clone 之后一条命令把仓库变成可运行状态。
#
#   ./scripts/bootstrap.sh
#
# 它做两件事：
#   1. 用 goods.csv 建倒排索引（纯 Java，约 5 秒，不需要网络）
#   2. 检查 model/ 下的模型权重与商品向量，缺失则下载
#
# 为什么大文件不进 git：
#   model/bge-small-zh-v1.5/encoder.onnx   91 MB   ← 唯一无法从仓库内容再生的
#   model/zero_shot_bge_small/corpus_emb.npy 98 MB   ← 有模型就能重建（18 秒）
#   index-data/goods/                       21 MB   ← goods.csv 就能重建（5 秒）
#   goods.csv、model.json 本身就是源码，正常跟踪
#
# 所以真正需要托管的只有 encoder.onnx 一个文件。用环境变量换下载地址：
#   HAWK_ASSETS_URL=... ./scripts/bootstrap.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ASSETS_URL="${HAWK_ASSETS_URL:-https://github.com/NoobCoderGrowing/Hawk/releases/download/assets-v1/hawk-assets.tar.gz}"
CORPUS="${HAWK_CORPUS:-goods.csv}"
INDEX_DIR="${HAWK_INDEX_DIR:-index-data/goods}"
MODEL_DIR="model/bge-small-zh-v1.5"
VECTOR_DIR="model/zero_shot_bge_small"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()  { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn(){ printf '  \033[33m!\033[0m %s\n' "$*"; }
die() { printf '\n\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 0. 环境

say "检查环境"

# 优先用 JAVA_HOME —— 很多机器上 PATH 里的 java 是旧版本，而 JDK 25 装在别处
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
else
  JAVA_BIN="$(command -v java || true)"
fi
[ -n "$JAVA_BIN" ] || die "找不到 java。本项目需要 JDK 22+（用了 java.lang.foreign）。"

JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 22 ] 2>/dev/null; then
  die "需要 JDK 22+，当前是 ${JAVA_MAJOR:-未知}（$JAVA_BIN）。
    索引文件用 MemorySegment 以 long 寻址来突破 2 GiB 单文件上限，FFM 在 JDK 22 才转正。

    指定一个 JDK 22+ 再跑：
      JAVA_HOME=/path/to/jdk-25 ./scripts/bootstrap.sh"
fi
ok "JDK $JAVA_MAJOR  ($JAVA_BIN)"

# 让 Maven 也用同一个 JDK
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$JAVA_BIN")")}"

command -v mvn >/dev/null || die "找不到 mvn。"
ok "Maven"

[ -f "$CORPUS" ] || die "找不到语料 $CORPUS —— 它是索引与向量的共同来源，必须存在。"
ok "语料 $CORPUS"

# ---------------------------------------------------------------- 1. 构建

say "构建"
mvn -q -DskipTests package
ok "全部模块编译完成"

# ---------------------------------------------------------------- 2. 倒排索引

say "倒排索引"
if [ -f "$INDEX_DIR/segment.info" ]; then
  ok "$INDEX_DIR 已存在，跳过（想重建就删掉它）"
else
  "$JAVA_BIN" -cp "demo/target/classes:demo/target/dependency/*" \
              demo.BuildIndex "$CORPUS" "$INDEX_DIR"
  ok "$INDEX_DIR"
fi

# ---------------------------------------------------------------- 3. 模型与向量

need_assets=0
[ -f "$MODEL_DIR/encoder.onnx" ]        || need_assets=1
[ -f "$VECTOR_DIR/corpus_emb.npy" ]     || need_assets=1

say "模型权重与商品向量"
if [ "$need_assets" = "0" ]; then
  ok "$MODEL_DIR 与 $VECTOR_DIR 均已就绪"
else
  warn "有缺失，尝试从 $ASSETS_URL 下载"

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
    mkdir -p model
    tar -xzf "$tmp/assets.tar.gz" -C model
    ok "已解压到 model/"
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
       放进 $MODEL_DIR/

EOF
    exit 1
  fi
fi

# ---------------------------------------------------------------- 4. 完成

say "就绪"
cat <<EOF
  启动 web 演示：
    java -Xmx3g -cp "web/target/classes:web/target/dependency/*" hawk.web.WebApplication
    打开 http://localhost:8080

  前端还没构建的话，先跑一次：
    cd web/frontend && npm install && npm run build
EOF
