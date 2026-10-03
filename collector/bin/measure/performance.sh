# shellcheck shell=bash
# M-03 / M-04（k6）: バックエンドを起動し、計測プロファイルの k6 シナリオで負荷をかける。
# measure.sh が source する（単独では実行しない）。

# シナリオ（collector/targets/<QG_TARGET>/）とツールの版（versions.env の K6_VERSION）は quality-gate 側のもの。
# 1 回に ウォームアップ + 計測 の時間がかかる。PERF_RUNS 回（既定 3 回）実行する。
# 中央値は quality-gate が取る（docs/metrics.md M-03）
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
  local script="$TARGET_DIR/$PERF_SCRIPT" port=${PERF_BACKEND_PORT:-8080} runs=${PERF_RUNS:-3}
  local warmup=${PERF_WARMUP_SECONDS:-60} duration=${PERF_DURATION_SECONDS:-300} k6 i summary environment jvm progress eta
  [ -f "$script" ] || { fail "M-03 / M-04: k6 のシナリオがありません（collector/targets/$QG_TARGET/$PERF_SCRIPT）"; return; }
  group "負荷試験（k6 ${K6_VERSION}、${runs} 回）"
  k6=$(k6_bin) || { fail "M-03 / M-04: k6 を取得できませんでした"; endgroup; return; }
  read -ra jvm <<< "${PERF_JAVA_OPTS:-}"
  start_backend "M-03 / M-04" "$port" "$WORK/perf-backend.log" "${PERF_START_TIMEOUT:-120}" "${jvm[@]}" \
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
  eta=$(date -u -d "@$(($(date +%s) + (warmup + duration) * runs))" +%H:%M:%S)
  log "負荷試験: 1 回 $((warmup + duration)) 秒（ウォームアップ ${warmup} 秒 + 計測 ${duration} 秒）× ${runs} 回、終了見込み ${eta} UTC"
  for ((i = 1; i <= runs; i++)); do
    summary="$REPORTS/perf/k6-summary-$i.json"
    log "負荷試験 ${i}/${runs} 回目: 開始"
    # k6 自身の進捗は --quiet で出さない（行が多すぎる）。代わりに一定間隔で経過を出す
    perf_progress "$i" "$runs" "$warmup" "$duration" &
    progress=$!
    # しきい値は必ず満たす条件だけなので、0 以外の終了は実行の異常（部分的な結果は判定に使わない）
    if PERF_BASE_URL="http://127.0.0.1:${port}" PERF_SUMMARY="$summary" \
        PERF_WARMUP_SECONDS="$warmup" PERF_DURATION_SECONDS="$duration" \
        "$k6" run --quiet --no-usage-report "$script"; then
      jq -cn --argjson e "$environment" '{environment: $e}' > "$summary.metadata"
      log "負荷試験 ${i}/${runs} 回目: 終了（$(perf_result "$summary")）"
    else
      warn "M-03 / M-04: ${i} 回目の k6 が異常終了しました"
      jq -cn --argjson e "$environment" '{environment: $e, aborted: true}' > "$summary.metadata"
    fi
    kill "$progress" 2>/dev/null || true
    wait "$progress" 2>/dev/null || true
  done
  stop_servers
  endgroup
}

# perf_progress <回> <回数> <ウォームアップ秒> <計測秒>。k6 の実行中に PERF_PROGRESS_INTERVAL 秒（既定 30 秒）ごとに経過を出す。
# measure_performance がバックグラウンドで起動し、k6 が終わったら止める
perf_progress() {
  local run=$1 runs=$2 warmup=$3 duration=$4 interval=${PERF_PROGRESS_INTERVAL:-30} start=$SECONDS elapsed phase
  while sleep "$interval"; do
    elapsed=$((SECONDS - start))
    if ((elapsed < warmup)); then
      phase="ウォームアップ ${elapsed}/${warmup} 秒"
    elif ((elapsed < warmup + duration)); then
      phase="計測 $((elapsed - warmup))/${duration} 秒"
    else
      phase="集計中"
    fi
    log "負荷試験 ${run}/${runs} 回目: ${phase}（経過 ${elapsed}/$((warmup + duration)) 秒）"
  done
}

# perf_result <summary>。計測区間の件数・p95・エラー率を 1 行にする（ログ用。読めなければ空）
perf_result() {
  jq -r '.metrics as $m
    | "件数 \($m["http_reqs{phase:measure}"].values.count)"
      + "、p95 \($m["http_req_duration{phase:measure}"].values["p(95)"] * 10 | round / 10) ms"
      + "、エラー率 \($m["http_req_failed{phase:measure}"].values.rate * 10000 | round / 100)%"' "$1" 2>/dev/null || true
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
