# shellcheck shell=bash
# M-16（Lighthouse。参考値）: 起動した画面（common.sh の start_app）を計測する。
# measure.sh が source する（単独では実行しない）。

# 画面ごとに LIGHTHOUSE_RUNS 回（既定 3 回）計測する。中央値は quality-gate が取る（1 回ごとの揺れが大きいため）
measure_lighthouse() {
  local tool="$WORK/lighthouse-tool" chrome pages page i n=0 output args=()
  if ! prepare_tool lighthouse "${QG_LIGHTHOUSE_TOOL_DIR:-}" "$tool"; then
    fail "M-16: Lighthouse を用意できませんでした"; return
  fi
  chrome=${A11Y_CHROMIUM:-$(cd "$A11Y_TOOL" && node --input-type=module \
    -e "import { chromium } from 'playwright'; console.log(chromium.executablePath())")}
  [ -x "$chrome" ] || { fail "M-16: Chromium が見つかりません（$chrome）"; return; }
  # 既定はデスクトップの条件（社内の業務画面を想定）。mobile にするとモバイルの回線・端末の条件になる
  [ "${LIGHTHOUSE_PRESET:-desktop}" = mobile ] || args+=("--preset=${LIGHTHOUSE_PRESET:-desktop}")
  mkdir -p "$REPORTS/frontend/lighthouse"
  read -ra pages <<< "$LIGHTHOUSE_PAGES"
  for page in "${pages[@]}"; do
    n=$((n + 1))
    for ((i = 1; i <= ${LIGHTHOUSE_RUNS:-3}; i++)); do
      output="$REPORTS/frontend/lighthouse/page${n}-run${i}.json"
      if ! CHROME_PATH="$chrome" node "$tool/node_modules/lighthouse/cli/index.js" "${APP_URL}${page}" \
          --quiet --output json --output-path "$output" \
          --only-categories=performance,accessibility,best-practices,seo \
          --chrome-flags="--headless=new --no-sandbox" "${args[@]}"; then
        rm -f "$output"
        warn "M-16: ${page} の ${i} 回目の計測に失敗しました"
      fi
    done
  done
  [ -n "$(ls -A "$REPORTS/frontend/lighthouse")" ] || fail "M-16: Lighthouse の結果がありません"
  rm -rf "$tool"
}
