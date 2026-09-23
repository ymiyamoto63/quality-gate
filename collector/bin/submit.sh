#!/usr/bin/env bash
# measure.sh がまとめた成果物を quality-gate の Ingest API に送る。
#
# 流れ: Run 作成 → 成果物のアップロード（あるものだけ） → finalize
# 仕様: docs/operations/ingest.md
#
# 使い方: submit.sh <reports ディレクトリ>
#
# 必須の環境変数:
#   QG_BASE_URL      取り込み先の quality-gate の URL
#   QG_INGEST_TOKEN  対象リポジトリの Ingest Token
# 任意の環境変数:
#   QG_TRIGGERED_BY  既定: collector（対象リポジトリの CI から送った Run と区別する）
#   QG_CI_RUN_URL    収集ワークフローの実行 URL
#
# .quality-gate.yml は送らない。判定には quality-gate の画面（S-06）で保存した設定が使われる。
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[ $# -eq 1 ] || die "使い方: submit.sh <reports ディレクトリ>"
REPORTS=$1
: "${QG_BASE_URL:?QG_BASE_URL が未設定です}"
: "${QG_INGEST_TOKEN:?QG_INGEST_TOKEN が未設定です}"

load_env "$REPORTS/meta.env"
load_profile "$QG_REPOSITORY"

API="${QG_BASE_URL%/}/api/v1/runs"
AUTH=(-H "Authorization: Bearer ${QG_INGEST_TOKEN}")

skipped_json() {
  local id
  for id in ${SKIP_METRICS:-}; do
    jq -n --arg id "$id" '{metricId: $id, reason: "収集ランナーでは計測していないため"}'
  done | jq -s '.'
}

REQUEST=$(jq -n \
  --arg repository "$QG_REPOSITORY" \
  --arg commitSha "$COMMIT_SHA" \
  --arg baseCommitSha "$BASE_SHA" \
  --arg branch "$BRANCH" \
  --arg pr "$PR_NUMBER" \
  --arg triggeredBy "${QG_TRIGGERED_BY:-collector}" \
  --arg ciRunUrl "${QG_CI_RUN_URL:-}" \
  --arg measuredAt "$(date -u +%FT%TZ)" \
  --argjson skippedMetrics "$(skipped_json)" \
  '{repository: $repository, commitSha: $commitSha, branch: $branch,
    runnerType: "self-hosted", triggeredBy: $triggeredBy, measuredAt: $measuredAt,
    skippedMetrics: $skippedMetrics}
   + (if $baseCommitSha != "" then {baseCommitSha: $baseCommitSha} else {} end)
   + (if $pr != "" then {pullRequestNumber: ($pr | tonumber)} else {} end)
   + (if $ciRunUrl != "" then {ciRunUrl: $ciRunUrl} else {} end)')

RUN_ID=$(curl -sS --fail-with-body -X POST "$API" "${AUTH[@]}" \
  -H 'Content-Type: application/json' -d "$REQUEST" | jq -r '.runId')
echo "Run を作成しました: $RUN_ID"

# upload <type> <file> [component] [scope] [metadata]
# ファイルが無ければ送らない。未提出の指標は quality-gate が ERROR（未計測）として扱う
upload() {
  local type=$1 file=$2 component=${3:-} scope=${4:-} metadata=${5:-}
  if [ ! -s "$file" ]; then
    warn "成果物がありません（type=$type）: $file"
    return 0
  fi
  local args=(-F "file=@${file}")
  [ -n "$metadata" ] && args+=(-F "metadata=${metadata}")
  local query="type=${type}"
  [ -n "$component" ] && query="${query}&component=${component}"
  [ -n "$scope" ] && query="${query}&scope=${scope}"
  curl -sS --fail-with-body -X POST "${API}/${RUN_ID}/artifacts?${query}" "${AUTH[@]}" "${args[@]}" >/dev/null
  echo "送信しました: type=$type ${component:+component=$component }${scope:+scope=$scope }${file#"$REPORTS"/}"
}

# コンポーネント名は計測プロファイルのディレクトリ名（backend / frontend）とする
BACKEND=${BACKEND_DIR##*/}
FRONTEND=${FRONTEND_DIR##*/}

if [ -n "${BACKEND_DIR:-}" ]; then
  upload jacoco-xml "$REPORTS/backend/jacoco.xml" "$BACKEND"
  upload pmd-xml "$REPORTS/backend/pmd.xml" "$BACKEND" head
  # base の解析結果があれば、M-07 は「新規・悪化した関数」を判定できる
  if [ -n "$BASE_SHA" ] && [ -s "$REPORTS/backend/pmd-base.xml" ]; then
    upload pmd-xml "$REPORTS/backend/pmd-base.xml" "$BACKEND" base
  fi
  found=0
  for junit in "$REPORTS"/contract/TEST-*.xml; do
    [ -e "$junit" ] || continue
    upload junit-xml "$junit" "$BACKEND"
    found=1
  done
  [ "$found" -eq 1 ] || warn "成果物がありません（type=junit-xml）: $REPORTS/contract/"
fi
[ -z "${FRONTEND_DIR:-}" ] || upload lcov "$REPORTS/frontend-coverage/lcov.info" "$FRONTEND"
if [ -n "${OPENAPI_PATH:-}" ]; then
  if [ -e "$REPORTS/oasdiff-base-spec-missing" ]; then
    upload oasdiff-json "$REPORTS/oasdiff.json" "$BACKEND" '' '{"baseSpecMissing":true}'
  else
    upload oasdiff-json "$REPORTS/oasdiff.json" "$BACKEND"
  fi
fi
upload sarif "$REPORTS/trivy.sarif"

curl -sS --fail-with-body -X POST "${API}/${RUN_ID}/finalize" "${AUTH[@]}" | jq .
