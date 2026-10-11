# shellcheck shell=bash
# M-08（Playwright + axe-core）: 起動した画面（common.sh の start_app）を、lib.sh の a11y_pages の画面について検査する
# （quality-gate の QG_ACCESSIBILITY_PAGES と計測プロファイルの A11Y_PAGES）。
# measure.sh が source する（単独では実行しない）。

measure_accessibility() {
  A11Y_BASE_URL="$APP_URL" A11Y_PAGES="$(a11y_pages)" \
    A11Y_READY_SELECTOR="${A11Y_READY_SELECTOR:-}" A11Y_OUTPUT="$REPORTS/frontend/axe-results.json" \
    node "$A11Y_TOOL/scan.mjs" || fail "M-08: 検査できなかった画面があります（その画面は ERROR になります）"
}
