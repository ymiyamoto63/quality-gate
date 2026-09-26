# shellcheck shell=bash
# M-01（TypeScript。Vitest + v8 カバレッジ）/ M-11・M-12（Vitest の junit reporter）: フロントエンドのテスト。
# measure.sh が source する（単独では実行しない）。

measure_frontend() {
  local dir="$SRC/$FRONTEND_DIR" version args=() pattern
  [ -f "$dir/package.json" ] || { fail "M-01: $FRONTEND_DIR/package.json がありません"; return; }
  group "フロントエンドのテスト（Vitest / v8 カバレッジ）"
  (
    set -e
    cd "$dir"
    npm ci --no-audit --no-fund
    version=$(node -p "require('vitest/package.json').version")
    # カバレッジのプロバイダが対象に無ければ、Vitest と同じ版を作業用の clone にだけ入れる
    if ! node -e "require.resolve('@vitest/coverage-v8/package.json')" 2>/dev/null; then
      npm install --no-save --no-audit --no-fund "@vitest/coverage-v8@${version}"
    fi
    args=(--coverage.enabled=true --coverage.provider=v8 --coverage.reportOnFailure=true
          --coverage.reporter=lcovonly "--coverage.reportsDirectory=$REPORTS/frontend-coverage")
    # パターンは空白区切り（{ts,vue} のようにカンマを含むため）。read は glob を展開しない
    read -ra patterns <<< "${FRONTEND_COVERAGE_INCLUDE:-}"
    for pattern in "${patterns[@]}"; do args+=("--coverage.include=$pattern"); done
    read -ra patterns <<< "${FRONTEND_COVERAGE_EXCLUDE:-}"
    for pattern in "${patterns[@]}"; do args+=("--coverage.exclude=$pattern"); done
    # テストの結果は JUnit XML でも出す（M-11 / M-12）。画面のログ用に default の reporter も残す
    args+=(--reporter=default --reporter=junit "--outputFile.junit=$REPORTS/tests/frontend/junit.xml")
    # テストの失敗では止めない（カバレッジは reportOnFailure で出る）
    npx vitest run "${args[@]}" || echo "::warning::フロントエンドのテストに失敗があります"
  ) || fail "フロントエンドの計測に失敗しました（M-01 TS / M-11 / M-12 は送られません）"
  endgroup
  [ -s "$REPORTS/frontend-coverage/lcov.info" ] || fail "M-01: lcov.info がありません"
  [ -s "$REPORTS/tests/frontend/junit.xml" ] || fail "M-11/M-12: フロントエンドのテストの結果（junit.xml）がありません"
}
