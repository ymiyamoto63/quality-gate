# shellcheck shell=bash
# M-15（jscpd。参考値）: backend と frontend のコードの重複を数える。
# measure.sh が source する（単独では実行しない）。

# run_jscpd <解析するディレクトリ> <出力> <除外（カンマ区切り）>
run_jscpd() {
  local target=$1 output=$2 ignore=$3 out="$WORK/jscpd-out"
  rm -rf "$out"
  "$JSCPD_TOOL/node_modules/.bin/jscpd" --silent --reporters json --output "$out" \
    ${ignore:+--ignore "$ignore"} "$target" || return 1
  [ -s "$out/jscpd-report.json" ] || return 1
  mv "$out/jscpd-report.json" "$output"
  rm -rf "$out"
}

measure_duplication() {
  local patterns pattern dir ignore=""
  JSCPD_TOOL="$WORK/jscpd-tool"
  group "コードの重複（jscpd）"
  if ! prepare_tool jscpd "${QG_JSCPD_TOOL_DIR:-}" "$JSCPD_TOOL"; then
    fail "M-15: jscpd を用意できませんでした"; endgroup; return
  fi
  if [ -n "${BACKEND_DIR:-}" ]; then
    run_jscpd "$SRC/$BACKEND_DIR/src/main/java" "$REPORTS/backend/jscpd-report.json" "" \
      || fail "M-15: jscpd（backend）の実行に失敗しました"
  fi
  if [ -n "${FRONTEND_DIR:-}" ]; then
    # 解析するディレクトリと除外は M-07（frontend）と同じ（テストと型定義は数えない）
    read -ra patterns <<< "${FRONTEND_COMPLEXITY_EXCLUDE:-**/*.spec.ts **/*.test.ts **/*.d.ts}"
    for pattern in "${patterns[@]}"; do ignore+="${ignore:+,}$pattern"; done
    read -ra patterns <<< "${FRONTEND_COMPLEXITY_SOURCES:-src}"
    # jscpd は 1 回に 1 つのディレクトリを受け取る。複数あれば最初のもの（多くは src）を解析する
    dir="$SRC/$FRONTEND_DIR/${patterns[0]}"
    run_jscpd "$dir" "$REPORTS/frontend/jscpd-report.json" "$ignore" \
      || fail "M-15: jscpd（frontend）の実行に失敗しました"
  fi
  rm -rf "$JSCPD_TOOL"
  endgroup
}
