#!/usr/bin/env bash
# 取得した対象リポジトリで計測し、成果物を reports/ にまとめる（M-01〜M-14。M-08 は欠番）。
#
# 使い方: measure.sh <owner/name> <作業ディレクトリ> <reports ディレクトリ>
#   作業ディレクトリには fetch.sh の出力（src/ と meta.env）があること。
#
# 対象のテストコードを実行するため、このスクリプトには認証情報を渡さない。
# 指標ごとの失敗は警告にとどめて続行する。未提出の指標は quality-gate が ERROR として扱う。
# 計測しない指標とその理由は reports/skipped-metrics.tsv に書き、submit.sh がスキップとして申告する。
#
# 指標ごとの計測は collector/bin/measure/ に分けてあり、このスクリプトは準備と実行の順序だけを持つ。
#
# 通常は measure-isolated.sh からコンテナの中で実行される（collector/runner/Dockerfile に必要なものがそろっている）。
# コンテナの外で直接実行するときに必要なもの:
#   git / curl / unzip / docker、JDK（バックエンド）、Node.js（フロントエンド）、Chromium の動作に必要なライブラリ（M-10）
# 環境変数:
#   QG_COLLECTOR_CACHE  PMD などを置くキャッシュ（既定: ~/.cache/quality-gate-collector）
#   A11Y_CHROMIUM       M-10 に使う Chromium の実行ファイル（任意。未指定なら Playwright が取得する）
#   QG_COLLECTOR_IN_CONTAINER  1 なら Trivy / oasdiff を Docker ではなくコンテナに入れたバイナリで実行する
#   QG_A11Y_TOOL_DIR    M-10 の検査ツールを取得済みのディレクトリ（コンテナのイメージに入っているもの）
#   QG_COMPLEXITY_TOOL_DIR  M-07（フロントエンド）の ESLint を取得済みのディレクトリ（コンテナのイメージに入っているもの）
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[ $# -eq 3 ] || die "使い方: measure.sh <owner/name> <作業ディレクトリ> <reports ディレクトリ>"
REPOSITORY=$1
WORK=$(cd "$2" && pwd)
mkdir -p "$3"
REPORTS=$(cd "$3" && pwd)
SRC="$WORK/src"
CACHE=${QG_COLLECTOR_CACHE:-$HOME/.cache/quality-gate-collector}

load_profile "$REPOSITORY"
load_env "$WORK/meta.env"
[ "$(git -C "$SRC" rev-parse HEAD)" = "$COMMIT_SHA" ] || die "作業ディレクトリのコミットが meta.env と一致しません"

FAILED=()
fail() { warn "$1"; FAILED+=("$1"); }
# skip <指標 ID> <理由>
skip() { printf '%s\t%s\n' "$1" "$2" >> "$REPORTS/skipped-metrics.tsv"; log "$1 は計測しません: $2"; }

cp "$WORK/meta.env" "$REPORTS/meta.env"
cp "$WORK/renames.json" "$REPORTS/renames.json"
cp "$COLLECTOR_DIR/versions.env" "$REPORTS/versions.env"
mkdir -p "$REPORTS/backend" "$REPORTS/frontend" "$REPORTS/tests/backend" "$REPORTS/tests/frontend"
rm -f "$REPORTS/skipped-metrics.tsv"

MEASURE_DIR="$COLLECTOR_DIR/bin/measure"
source "$MEASURE_DIR/common.sh"
for metric in backend-tests mutation complexity frontend-tests accessibility \
    performance breaking-changes vulnerabilities licenses; do
  source "$MEASURE_DIR/$metric.sh"
done

# M-10。対象アプリを起動して検査する
measure_app() {
  local label=M-10
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

prepare_base
if [ -n "${BACKEND_DIR:-}" ]; then
  measure_backend
  [ -z "${MUTATION_TARGET_CLASSES:-}" ] || measure_mutation
  measure_complexity
fi
if [ -n "${FRONTEND_DIR:-}" ]; then
  measure_frontend
  measure_frontend_complexity
fi
cleanup_base
if [ -n "${FRONTEND_DIR:-}" ] && [ -n "${A11Y_PAGES:-}" ]; then
  build_frontend
  measure_app
fi
[ -z "${PERF_SCRIPT:-}" ] || measure_performance
[ -z "${OPENAPI_PATH:-}" ] || measure_breaking_changes
measure_vulnerabilities
measure_licenses

log "計測結果:"
(cd "$REPORTS" && find . -type f ! -name '*.env' ! -name '*.tsv' | sort | sed 's/^/  /') >&2
if [ ${#FAILED[@]} -gt 0 ]; then
  warn "計測できなかったものがあります（${#FAILED[@]} 件）。該当の指標は ERROR になります"
  printf '  - %s\n' "${FAILED[@]}" >&2
fi
