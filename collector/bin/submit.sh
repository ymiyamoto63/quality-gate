#!/usr/bin/env bash
# measure.sh がまとめた成果物を quality-gate の Ingest API に送る。
#
# 流れ: Run 作成 → 成果物のアップロード（あるものだけ） → finalize（その場で判定され、結果が返る）
# 仕様: docs/features/ingest/design.md
#
# 使い方: submit.sh <reports ディレクトリ>
#
# 必須の環境変数:
#   QG_BASE_URL      取り込み先の quality-gate の URL
#   QG_INGEST_TOKEN  quality-gate の Ingest Token（すべての対象で共通）
# 任意の環境変数:
#   QG_CI_RUN_URL    収集ワークフローの実行 URL
#
# 合格ラインは送らない。quality-gate が自分の環境変数（QG_*）の合格ラインで判定する。
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[ $# -eq 1 ] || die "使い方: submit.sh <reports ディレクトリ>"
REPORTS=$1
: "${QG_BASE_URL:?QG_BASE_URL が未設定です}"
: "${QG_INGEST_TOKEN:?QG_INGEST_TOKEN が未設定です}"

load_env "$REPORTS/meta.env"
load_profile

API="${QG_BASE_URL%/}/api/v1/runs"
AUTH=(-H "Authorization: Bearer ${QG_INGEST_TOKEN}")
# curl の --retry は、一時的な障害（5xx など）を再試行する

REQUEST=$(jq -n \
  --arg repository "$QG_REPOSITORY" \
  --arg commitSha "$COMMIT_SHA" \
  --arg baseCommitSha "$BASE_SHA" \
  --arg branch "$BRANCH" \
  --arg defaultBranch "$DEFAULT_BRANCH" \
  --arg ciRunUrl "${QG_CI_RUN_URL:-}" \
  --arg measuredAt "$(date -u +%FT%TZ)" \
  --arg tags "${TAGS:-}" \
  '{repository: $repository, commitSha: $commitSha, branch: $branch, defaultBranch: $defaultBranch,
    triggeredBy: "collector", measuredAt: $measuredAt,
    tags: ($tags | split(" ") | map(select(. != "")))}
   + (if $baseCommitSha != "" then {baseCommitSha: $baseCommitSha} else {} end)
   + (if $ciRunUrl != "" then {ciRunUrl: $ciRunUrl} else {} end)')

RUN_ID=$(curl -sS --retry 3 --fail-with-body -X POST "$API" "${AUTH[@]}" \
  -H 'Content-Type: application/json' -d "$REQUEST" | jq -r '.runId')
echo "Run を作成しました: $RUN_ID"

# upload <type> <file> [component] [metadata]
# ファイルが無ければ送らない。未提出の指標は quality-gate が ERROR（計測エラー）として扱う
upload() {
  local type=$1 file=$2 component=${3:-} metadata=${4:-}
  if [ ! -s "$file" ]; then
    warn "成果物がありません（type=$type）: $file"
    return 0
  fi
  local args=(-F "file=@${file}")
  [ -n "$metadata" ] && args+=(-F "metadata=${metadata}")
  local query="type=${type}"
  [ -n "$component" ] && query="${query}&component=${component}"
  curl -sS --retry 3 --fail-with-body -X POST "${API}/${RUN_ID}/artifacts?${query}" "${AUTH[@]}" "${args[@]}" >/dev/null
  echo "送信しました: type=$type ${component:+component=$component }${file#"$REPORTS"/}"
}

# コンポーネント名は計測プロファイルのディレクトリ名（backend / frontend）とする
BACKEND=${BACKEND_DIR##*/}
FRONTEND=${FRONTEND_DIR##*/}

if [ -n "${BACKEND_DIR:-}" ]; then
  upload jacoco-xml "$REPORTS/backend/jacoco.xml" "$BACKEND"
  # 収集ランナーの PIT は常に全量（変更範囲への絞り込みはしない）
  if [ -n "${MUTATION_TARGET_CLASSES:-}" ] && metric_enabled M-02; then
    upload pit-xml "$REPORTS/backend/mutations.xml" "$BACKEND" '{"mutationScope":"all"}'
  fi
  upload pmd-xml "$REPORTS/backend/pmd.xml" "$BACKEND"
  # M-09 / M-10 はすべてのテストの結果（test-junit-xml）
  found=0
  for junit in "$REPORTS"/tests/backend/TEST-*.xml; do
    [ -e "$junit" ] || continue
    upload test-junit-xml "$junit" "$BACKEND"
    found=1
  done
  [ "$found" -eq 1 ] || warn "成果物がありません（type=test-junit-xml）: $REPORTS/tests/backend/"
fi
if [ -n "${FRONTEND_DIR:-}" ]; then
  upload lcov "$REPORTS/frontend-coverage/lcov.info" "$FRONTEND"
  upload test-junit-xml "$REPORTS/tests/frontend/junit.xml" "$FRONTEND"
  upload eslint-json "$REPORTS/frontend/eslint.json" "$FRONTEND"
fi
[ -z "${A11Y_PAGES:-}" ] || upload axe-json "$REPORTS/frontend/axe-results.json" "$FRONTEND"
if [ -n "${OPENAPI_PATH:-}" ]; then
  if [ -e "$REPORTS/oasdiff-base-spec-missing" ]; then
    upload oasdiff-json "$REPORTS/oasdiff.json" "$BACKEND" '{"baseSpecMissing":true}'
  else
    upload oasdiff-json "$REPORTS/oasdiff.json" "$BACKEND"
  fi
fi
# 走査した対象を申告する（申告の無い SARIF は、すべて M-05 として読まれる）
upload sarif "$REPORTS/trivy.sarif" '' '{"scanners":["vuln","secret"]}'
upload sarif "$REPORTS/trivy-license.sarif" '' '{"scanners":["license"]}'
# M-03 / M-04。1 ファイル = 1 回の実行。計測環境（と異常終了）は measure.sh が書いた .metadata を添える
if [ -n "${PERF_SCRIPT:-}" ] && metric_enabled M-03; then
  found=0
  for summary in "$REPORTS"/perf/k6-summary-*.json; do
    [ -e "$summary" ] || continue
    upload k6-summary "$summary" "$BACKEND" "$(cat "$summary.metadata")"
    found=1
  done
  [ "$found" -eq 1 ] || warn "成果物がありません（type=k6-summary）: $REPORTS/perf/"
fi

# 確定するとその場で判定される。判定に時間がかかる Run があるため、再試行はしない（二重に確定すると 409 になる）
RESULT=$(curl -sS --fail-with-body --max-time 600 -X POST "${API}/${RUN_ID}/finalize" "${AUTH[@]}")
echo "$RESULT" | jq .
STATUS=$(echo "$RESULT" | jq -r '.status')
echo "判定: $(echo "$RESULT" | jq -r '.verdict // "—"')（$(echo "$RESULT" | jq -r '.detailUrl')）"
# 処理失敗（設定の誤りなど）は計測のやり直しでは直らないため、ワークフローを失敗にして気づけるようにする。
# 判定結果の不合格（FAIL）はワークフローの失敗にしない（品質の結果であって、計測の失敗ではない）
[ "$STATUS" = "EVALUATED" ] || die "判定に失敗しました（$(echo "$RESULT" | jq -r '.errorCode')）。理由は quality-gate のログを確認してください"
