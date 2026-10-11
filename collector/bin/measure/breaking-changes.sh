# shellcheck shell=bash
# M-07（oasdiff）: コミットされている OpenAPI 定義を head と base で比べる。
# measure.sh が source する（単独では実行しない）。

measure_breaking_changes() {
  group "OpenAPI の破壊的変更（oasdiff ${OASDIFF_VERSION}）"
  rm -f "$REPORTS/oasdiff-base-spec-missing" "$REPORTS/oasdiff.json"
  if ! git -C "$SRC" show "$COMMIT_SHA:$OPENAPI_PATH" > "$REPORTS/openapi-head.yml" 2>/dev/null; then
    rm -f "$REPORTS/openapi-head.yml"
    fail "M-07: head に $OPENAPI_PATH がありません"
    endgroup
    return
  fi
  rm -f "$REPORTS/openapi-base.yml"
  if [ -n "$BASE_SHA" ] && git -C "$SRC" cat-file -e "$BASE_SHA:$OPENAPI_PATH" 2>/dev/null; then
    git -C "$SRC" show "$BASE_SHA:$OPENAPI_PATH" > "$REPORTS/openapi-base.yml"
    # 出力は oasdiff.json.tmp に書いてから移す。oasdiff は起動時にカレントディレクトリの oasdiff.{json,yaml,…} を
    # 設定ファイルとして読むため、リダイレクトで先にできる空の oasdiff.json を読んで失敗する
    if (cd "$REPORTS" && oasdiff breaking openapi-base.yml openapi-head.yml --format json) > "$REPORTS/oasdiff.json.tmp"; then
      mv "$REPORTS/oasdiff.json.tmp" "$REPORTS/oasdiff.json"
      # 変更が無いときの空出力は 0 件として送る
      [ -s "$REPORTS/oasdiff.json" ] || echo '[]' > "$REPORTS/oasdiff.json"
    else
      rm -f "$REPORTS/oasdiff.json" "$REPORTS/oasdiff.json.tmp"
      fail "M-07: oasdiff の実行に失敗しました"
    fi
  else
    # 比較元に定義が無い（新規 API）。quality-gate は M-07 を対象外として扱う
    echo '[]' > "$REPORTS/oasdiff.json"
    touch "$REPORTS/oasdiff-base-spec-missing"
  fi
  endgroup
}
