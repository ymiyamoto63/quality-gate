# shellcheck shell=bash
# M-09（Playwright + axe-core）: 起動した画面（common.sh の start_app）を、計測プロファイルの A11Y_PAGES について検査する。
# measure.sh が source する（単独では実行しない）。

measure_accessibility() {
  A11Y_BASE_URL="$APP_URL" A11Y_PAGES="$A11Y_PAGES" \
    A11Y_READY_SELECTOR="${A11Y_READY_SELECTOR:-}" A11Y_OUTPUT="$REPORTS/frontend/axe-results.json" \
    node "$A11Y_TOOL/scan.mjs" || fail "M-09: 検査できなかった画面があります（その画面は ERROR になります）"
}
