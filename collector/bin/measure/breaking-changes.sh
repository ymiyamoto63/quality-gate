# shellcheck shell=bash
# M-09（oasdiff）: コミットされている OpenAPI 定義を head と base で比べる。
# measure.sh が source する（単独では実行しない）。

# コンテナの中ではイメージに入れたバイナリ、外では版を固定した Docker イメージで動かす
oasdiff() {
  if [ "${QG_COLLECTOR_IN_CONTAINER:-}" = 1 ]; then
    command oasdiff "$@"
  else
    docker run --rm -v "$PWD:/w:ro" -w /w "$OASDIFF_IMAGE" "$@"
  fi
}

measure_breaking_changes() {
  group "OpenAPI の破壊的変更（${OASDIFF_IMAGE}）"
  rm -f "$REPORTS/oasdiff-base-spec-missing"
  if ! git -C "$SRC" show "$COMMIT_SHA:$OPENAPI_PATH" > "$REPORTS/openapi-head.yml" 2>/dev/null; then
    rm -f "$REPORTS/openapi-head.yml"
    fail "M-09: head に $OPENAPI_PATH がありません"
    endgroup
    return
  fi
  rm -f "$REPORTS/openapi-base.yml"
  if [ -n "$BASE_SHA" ] && git -C "$SRC" cat-file -e "$BASE_SHA:$OPENAPI_PATH" 2>/dev/null; then
    git -C "$SRC" show "$BASE_SHA:$OPENAPI_PATH" > "$REPORTS/openapi-base.yml"
    if (cd "$REPORTS" && oasdiff breaking openapi-base.yml openapi-head.yml --format json) > "$REPORTS/oasdiff.json"; then
      # 変更が無いときの空出力は 0 件として送る
      [ -s "$REPORTS/oasdiff.json" ] || echo '[]' > "$REPORTS/oasdiff.json"
    else
      rm -f "$REPORTS/oasdiff.json"
      fail "M-09: oasdiff の実行に失敗しました"
    fi
  else
    # 比較元に定義が無い（新規 API）。quality-gate は M-09 を対象外として扱う
    echo '[]' > "$REPORTS/oasdiff.json"
    touch "$REPORTS/oasdiff-base-spec-missing"
  fi
  endgroup
}
