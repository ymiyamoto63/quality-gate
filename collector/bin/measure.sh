#!/usr/bin/env bash
# 取得した対象リポジトリで計測し、成果物を reports/ にまとめる（M-01〜M-12）。
#
# 使い方: measure.sh <作業ディレクトリ> <reports ディレクトリ>
#   作業ディレクトリには fetch.sh の出力（src/ と meta.env）があること。
#
# 対象のテストコードを実行するため、このスクリプトには認証情報を渡さない。
# 指標ごとの失敗は警告にとどめて続行する。未提出の指標は quality-gate が ERROR として扱う。
# 計測プロファイルの DISABLED_METRICS に書いた指標は計測しない（quality-gate の QG_DISABLED_METRICS とそろえる）。
#
# 指標ごとの計測は collector/bin/measure/ に分けてあり、このスクリプトは準備と実行の順序だけを持つ。
#
# measure-isolated.sh からコンテナの中で実行される（collector/runner/Dockerfile に必要なものがそろっている）。
# 環境変数（コンテナのイメージが設定する）:
#   QG_A11Y_TOOL_DIR    M-08 の検査ツールと Chromium を取得済みのディレクトリ
#   QG_COMPLEXITY_TOOL_DIR  M-06（フロントエンド）の ESLint を取得済みのディレクトリ
#   QG_COLLECTOR_CACHE  PMD などを置くキャッシュ（任意。既定: ~/.cache/quality-gate-collector）
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[ $# -eq 2 ] || die "使い方: measure.sh <作業ディレクトリ> <reports ディレクトリ>"
[ -n "${QG_A11Y_TOOL_DIR:-}" ] && [ -n "${QG_COMPLEXITY_TOOL_DIR:-}" ] \
  || die "measure.sh は measure-isolated.sh からコンテナの中で実行してください"
WORK=$(cd "$1" && pwd)
mkdir -p "$2"
REPORTS=$(cd "$2" && pwd)
SRC="$WORK/src"
CACHE=${QG_COLLECTOR_CACHE:-$HOME/.cache/quality-gate-collector}

load_profile
load_env "$WORK/meta.env"
[ "$(git -C "$SRC" rev-parse HEAD)" = "$COMMIT_SHA" ] || die "作業ディレクトリのコミットが meta.env と一致しません"

FAILED=()
fail() { warn "$1"; FAILED+=("$1"); }

cp "$WORK/meta.env" "$REPORTS/meta.env"
cp "$COLLECTOR_DIR/versions.env" "$REPORTS/versions.env"
mkdir -p "$REPORTS/backend" "$REPORTS/frontend" "$REPORTS/tests/backend" "$REPORTS/tests/frontend"

MEASURE_DIR="$COLLECTOR_DIR/bin/measure"
source "$MEASURE_DIR/common.sh"
for metric in backend-tests mutation complexity frontend-tests accessibility \
    performance breaking-changes vulnerabilities licenses; do
  source "$MEASURE_DIR/$metric.sh"
done

if [ -n "${DISABLED_METRICS:-}" ]; then
  log "計測しない指標（DISABLED_METRICS）: $DISABLED_METRICS"
fi

# M-08。対象アプリを起動して検査する
measure_app() {
  local label=M-08
  [ -f "$SRC/$FRONTEND_DIR/package.json" ] || { fail "$label: $FRONTEND_DIR/package.json がありません"; return; }
  group "画面の検査（${label}）"
  if ! prepare_a11y_tool; then
    fail "$label: 検査ツール（Playwright と Chromium）を用意できませんでした"; endgroup; return
  fi
  if start_app "$label"; then
    measure_accessibility
  fi
  stop_servers
  endgroup
  rm -rf "$A11Y_TOOL"
}

# テスト（M-01 / M-09 / M-10）はビルドを兼ね、M-02・M-03 / M-04・M-08 が使う成果物を作るため、常に実行する
if [ -n "${BACKEND_DIR:-}" ]; then
  measure_backend
  if [ -n "${MUTATION_TARGET_CLASSES:-}" ] && metric_enabled M-02; then measure_mutation; fi
  if metric_enabled M-06; then measure_complexity; fi
fi
if [ -n "${FRONTEND_DIR:-}" ]; then
  measure_frontend
  if metric_enabled M-06; then measure_frontend_complexity; fi
fi
if [ -n "${FRONTEND_DIR:-}" ] && [ -n "${A11Y_PAGES:-}" ] && metric_enabled M-08; then
  build_frontend
  measure_app
fi
if [ -n "${PERF_SCRIPT:-}" ] && metric_enabled M-03; then measure_performance; fi
if [ -n "${OPENAPI_PATH:-}" ] && metric_enabled M-07; then measure_breaking_changes; fi
# M-05 と M-11 は 1 回の走査で両方を出す
if metric_enabled M-05 || metric_enabled M-11; then measure_vulnerabilities; fi
if metric_enabled M-12; then measure_licenses; fi

log "計測結果:"
(cd "$REPORTS" && find . -type f ! -name '*.env' ! -name '*.tsv' | sort | sed 's/^/  /') >&2
if [ ${#FAILED[@]} -gt 0 ]; then
  warn "計測できなかったものがあります（${#FAILED[@]} 件）。該当の指標は ERROR になります"
  printf '  - %s\n' "${FAILED[@]}" >&2
fi
