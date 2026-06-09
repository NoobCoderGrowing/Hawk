#!/usr/bin/env bash
# Shared helpers for Hawk/Lucene benchmark scripts.

init_result_file() {
  local prefix="$1"
  RESULT_DIR="${RESULT_DIR:-${BENCHMARK_DIR}/results}"
  mkdir -p "${RESULT_DIR}"
  if [[ -z "${RESULT_FILE:-}" ]]; then
    RESULT_FILE="${RESULT_DIR}/${prefix}-$(date +%Y%m%d-%H%M%S).txt"
  fi
  {
    echo "=== Benchmark run: ${prefix} ==="
    echo "started: $(date -Iseconds)"
    echo "index_dir: ${INDEX_DIR}"
    echo "doc_counts: ${DOC_COUNTS}"
    echo "jmh_args: ${JMH_ARGS}"
    echo "available_processors: $(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo unknown)"
    echo "jar: ${JAR}"
    echo
  } | tee "${RESULT_FILE}"
}

log() {
  echo "$@" | tee -a "${RESULT_FILE}"
}

ensure_jar() {
  if [[ "${BUILD}" == "0" && -f "${JAR}" ]]; then
    log "[${BENCHMARK_PREFIX}] skip build (BUILD=0, jar exists): ${JAR}"
    return
  fi
  log "[${BENCHMARK_PREFIX}] build benchmarks.jar"
  mvn -q -pl benchmark -am package -DskipTests -f "${PROJECT_ROOT}/pom.xml"
}

wipe_index_dir() {
  log "[${BENCHMARK_PREFIX}] wipe index dir: ${INDEX_DIR}"
  rm -rf "${INDEX_DIR}"
  mkdir -p "${INDEX_DIR}"
}

run_jmh() {
  local jvm_index_property="$1"
  local benchmark_name="$2"
  shift 2
  log "[${BENCHMARK_PREFIX}] run ${benchmark_name} $*"
  {
    java "${jvm_index_property}=${INDEX_DIR}" \
      -jar "${JAR}" \
      "${benchmark_name}" \
      ${JMH_ARGS} \
      "$@"
  } 2>&1 | tee -a "${RESULT_FILE}"
}

finish() {
  log "[${BENCHMARK_PREFIX}] done"
  log "result file: ${RESULT_FILE}"
}
