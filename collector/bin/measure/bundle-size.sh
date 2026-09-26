# shellcheck shell=bash
# M-17（バンドルサイズ。参考値）: ビルドした画面のファイルサイズを数える。
# measure.sh が source する（単独では実行しない）。

measure_bundle_size() {
  local dist="$SRC/$FRONTEND_DIR/${FRONTEND_DIST:-dist}"
  [ "$FRONTEND_BUILD_OK" = 1 ] || { fail "M-17: フロントエンドのビルドに失敗しました"; return; }
  [ -d "$dist" ] || { fail "M-17: ビルド結果（$FRONTEND_DIR/${FRONTEND_DIST:-dist}）がありません"; return; }
  node "$COLLECTOR_DIR/bundle/size.mjs" "$dist" "$REPORTS/frontend/bundle-size.json" \
    || fail "M-17: バンドルサイズを数えられませんでした"
}
