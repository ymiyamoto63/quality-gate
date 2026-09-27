# shellcheck shell=bash
# M-06: 循環的複雑度。バックエンドは PMD、フロントエンドは ESLint の complexity ルールで、計測するコミットを解析する。
# measure.sh が source する（単独では実行しない）。

pmd_bin() {
  local home="$CACHE/pmd-bin-${PMD_VERSION}"
  if [ ! -x "$home/bin/pmd" ]; then
    mkdir -p "$CACHE"
    curl -fsSL -o "$CACHE/pmd.zip" \
      "https://github.com/pmd/pmd/releases/download/pmd_releases%2F${PMD_VERSION}/pmd-dist-${PMD_VERSION}-bin.zip"
    rm -rf "$home"
    unzip -q -o "$CACHE/pmd.zip" -d "$CACHE"
    rm -f "$CACHE/pmd.zip"
  fi
  echo "$home/bin/pmd"
}

run_pmd() {
  local sources=$1 output=$2
  "$PMD" check --no-cache --no-progress --no-fail-on-violation \
    -R "$COLLECTOR_DIR/pmd-ruleset.xml" -f xml -d "$sources" -r "$output"
}

measure_complexity() {
  local sources="$SRC/$BACKEND_DIR/src/main/java"
  [ -d "$sources" ] || { fail "M-06: $BACKEND_DIR/src/main/java がありません"; return; }
  group "循環的複雑度（PMD ${PMD_VERSION}）"
  PMD=$(pmd_bin) || { fail "M-06: PMD を取得できませんでした"; endgroup; return; }
  run_pmd "$sources" "$REPORTS/backend/pmd.xml" || fail "M-06: PMD の実行に失敗しました"
  endgroup
}

# --- フロントエンド -------------------------------------------------------------------
# 対象の ESLint の設定は使わず、quality-gate 側の設定と版（collector/complexity）で解析する
# run_eslint <作業ツリーのルート> <出力>
run_eslint() {
  local root=$1 output=$2 args=() patterns pattern status=0
  read -ra patterns <<< "${FRONTEND_COMPLEXITY_EXCLUDE:-**/*.spec.ts **/*.test.ts **/*.d.ts}"
  for pattern in "${patterns[@]}"; do args+=(--ignore-pattern "$pattern"); done
  read -ra patterns <<< "${FRONTEND_COMPLEXITY_SOURCES:-src}"
  # 終了コード 1 は構文を読めなかったファイルがあるとき（complexity は warn のため、それ以外では 0）
  (cd "$root/$FRONTEND_DIR" && node "$COMPLEXITY_TOOL/node_modules/eslint/bin/eslint.js" \
    -c "$COMPLEXITY_TOOL/eslint.config.mjs" --no-warn-ignored -f json -o "$output.raw" \
    "${args[@]}" "${patterns[@]}") || status=$?
  if [ "$status" -gt 1 ] || [ ! -s "$output.raw" ]; then
    rm -f "$output.raw"
    return 1
  fi
  # 作業ツリーの場所（計測ごとに違う）をパスから外し、/<FRONTEND_DIR>/src/... の形にそろえる。
  # quality-gate はこの形からモジュール相対（src/...）とリポジトリ相対（frontend/src/...）のパスを求める
  jq --arg root "$root" 'map(.filePath |= ltrimstr($root))' "$output.raw" > "$output"
  rm -f "$output.raw"
  local unreadable
  unreadable=$(jq '[.[] | select(any(.messages[]; .fatal == true))] | length' "$output")
  [ "$unreadable" -eq 0 ] || warn "M-06: 構文を読めず、関数を数えられなかったファイルがあります（${unreadable} 件）"
}

measure_frontend_complexity() {
  COMPLEXITY_TOOL="$WORK/complexity-tool"
  [ -d "$SRC/$FRONTEND_DIR" ] || { fail "M-06: $FRONTEND_DIR がありません"; return; }
  group "循環的複雑度（フロントエンド、ESLint の complexity ルール）"
  # ESLint とパーサはコンテナのイメージに入っている
  if ! prepare_tool complexity "$QG_COMPLEXITY_TOOL_DIR" "$COMPLEXITY_TOOL" eslint.config.mjs; then
    fail "M-06: ESLint を用意できませんでした（フロントエンドの複雑度は送られません）"; endgroup; return
  fi

  run_eslint "$SRC" "$REPORTS/frontend/eslint.json" || fail "M-06: ESLint の実行に失敗しました"
  rm -rf "$COMPLEXITY_TOOL"
  endgroup
}
