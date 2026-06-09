#!/usr/bin/env bash
# Run Lucene JMH benchmarks. Wipes the index directory before each index-writing run.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BENCHMARK_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROJECT_ROOT="$(cd "${BENCHMARK_DIR}/.." && pwd)"
# shellcheck source=benchmark-common.sh
source "${SCRIPT_DIR}/benchmark-common.sh"

JAR="${JAR:-${BENCHMARK_DIR}/target/benchmarks.jar}"
INDEX_DIR="${LUCENE_BENCHMARK_INDEX_DIR:-/tmp/lucene-jmh-index}"
DOC_COUNTS="${DOC_COUNTS:-1000 5000 10000}"
JMH_ARGS="${JMH_ARGS:--i 1 -wi 1 -f 1}"
BUILD="${BUILD:-1}"
BENCHMARK_PREFIX="lucene"

init_result_file "lucene"
ensure_jar

log "=== Lucene index throughput ==="
for doc_count in ${DOC_COUNTS}; do
  wipe_index_dir
  run_jmh -Dlucene.benchmark.index.dir LuceneIndexBenchmark -p "docCount=${doc_count}"
done

log "=== Lucene search throughput ==="
wipe_index_dir
run_jmh -Dlucene.benchmark.index.dir LuceneSearchBenchmark

finish
