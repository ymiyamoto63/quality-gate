# shellcheck shell=bash
# M-03〜05（k6）: バックエンドを起動し、計測プロファイルの k6 シナリオで負荷をかける。
# measure.sh が source する（単独では実行しない）。

# シナリオ（collector/targets/）とツールの版（versions.env の K6_VERSION）は quality-gate 側のもの。
# 1 回に ウォームアップ + 計測 の時間がかかるため、PR 以外の計測で PERF_RUNS 回（既定 3 回）実行する。
# 中央値は quality-gate が取る（docs/initial/02-metrics-spec.md M-03）
k6_bin() {
  local home="$CACHE/k6-${K6_VERSION}" arch
  if [ ! -x "$home/k6" ]; then
    arch=$(uname -m | sed 's/x86_64/amd64/; s/aarch64/arm64/')
    mkdir -p "$home"
    curl -fsSL "https://github.com/grafana/k6/releases/download/v${K6_VERSION}/k6-v${K6_VERSION}-linux-${arch}.tar.gz" \
      | tar -xz -C "$home" --strip-components=1 "k6-v${K6_VERSION}-linux-${arch}/k6"
  fi
  echo "$home/k6"
}

measure_performance() {
  local script="$COLLECTOR_DIR/targets/$PERF_SCRIPT" port=${PERF_BACKEND_PORT:-8080} runs=${PERF_RUNS:-3}
  local warmup=${PERF_WARMUP_SECONDS:-60} duration=${PERF_DURATION_SECONDS:-300} k6 i summary environment jvm
  if [ -n "$PR_NUMBER" ]; then
    for i in M-03 M-04 M-05; do
      skip "$i" "収集ランナーは PR の計測では負荷試験を実行しない"
    done
    return
  fi
  [ -f "$script" ] || { fail "M-03〜05: k6 のシナリオがありません（collector/targets/$PERF_SCRIPT）"; return; }
  group "負荷試験（k6 ${K6_VERSION}、${runs} 回）"
  k6=$(k6_bin) || { fail "M-03〜05: k6 を取得できませんでした"; endgroup; return; }
  read -ra jvm <<< "${PERF_JAVA_OPTS:-}"
  start_backend M-03〜05 "$port" "$WORK/perf-backend.log" "${PERF_START_TIMEOUT:-120}" "${jvm[@]}" \
    || { endgroup; return; }

  # 計測環境。名前はトレンドの系列を分ける軸になる（構成を変えたら名前も変える）
  environment=$(jq -cn \
    --arg name "${PERF_ENVIRONMENT:-collector}" \
    --argjson cpu "$(nproc)" \
    --arg memory "$(memory_limit)" \
    --arg dataset "${PERF_DATASET_PROFILE:-}" \
    --arg k6 "$K6_VERSION" \
    --argjson warmup "$warmup" --argjson duration "$duration" \
    '{name: $name, runner: "self-hosted", cpu: $cpu, memory: $memory, k6: $k6,
      warmupSeconds: $warmup, durationSeconds: $duration}
     + (if $dataset != "" then {datasetProfile: $dataset} else {} end)')
  mkdir -p "$REPORTS/perf"
  for ((i = 1; i <= runs; i++)); do
    summary="$REPORTS/perf/k6-summary-$i.json"
    log "負荷試験 ${i}/${runs} 回目"
    # しきい値は必ず満たす条件だけなので、0 以外の終了は実行の異常（部分的な結果は判定に使わない）
    if PERF_BASE_URL="http://127.0.0.1:${port}" PERF_SUMMARY="$summary" \
        PERF_WARMUP_SECONDS="$warmup" PERF_DURATION_SECONDS="$duration" \
        "$k6" run --quiet --no-usage-report "$script"; then
      jq -cn --argjson e "$environment" '{environment: $e}' > "$summary.metadata"
    else
      warn "M-03〜05: ${i} 回目の k6 が異常終了しました"
      jq -cn --argjson e "$environment" '{environment: $e, aborted: true}' > "$summary.metadata"
    fi
  done
  stop_servers
  endgroup
}

# コンテナのメモリ上限（cgroup v2 / v1）。上限が無ければマシンのメモリ
memory_limit() {
  local limit
  limit=$(cat /sys/fs/cgroup/memory.max 2>/dev/null || cat /sys/fs/cgroup/memory/memory.limit_in_bytes 2>/dev/null || true)
  if [ -z "$limit" ] || [ "$limit" = max ] || [ "$limit" -ge 9000000000000000000 ] 2>/dev/null; then
    limit=$(awk '/MemTotal/ {print $2 * 1024}' /proc/meminfo)
  fi
  echo "$((limit / 1024 / 1024))MiB"
}
