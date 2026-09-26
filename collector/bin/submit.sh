#!/usr/bin/env bash
# measure.sh がまとめた成果物を quality-gate の Ingest API に送る。
#
# 流れ: Run 作成 → 成果物のアップロード（あるものだけ） → finalize（その場で判定され、結果が返る）
# 仕様: docs/operations/ingest.md
#
# 使い方: submit.sh <reports ディレクトリ>
#
# 必須の環境変数:
#   QG_BASE_URL      取り込み先の quality-gate の URL
#   QG_INGEST_TOKEN  quality-gate の Ingest Token（すべての対象で共通）
# 任意の環境変数:
#   QG_TRIGGERED_BY  既定: collector（対象リポジトリの CI から送った Run と区別する）
#   QG_CI_RUN_URL    収集ワークフローの実行 URL
#
# 合格ライン（collector/targets/<owner>__<name>.gate.yml）も Run ごとに送る。判定はこの設定で行われる（D-20）。
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[ $# -eq 1 ] || die "使い方: submit.sh <reports ディレクトリ>"
REPORTS=$1
: "${QG_BASE_URL:?QG_BASE_URL が未設定です}"
: "${QG_INGEST_TOKEN:?QG_INGEST_TOKEN が未設定です}"

load_env "$REPORTS/meta.env"
load_profile "$QG_REPOSITORY"

# 合格ラインが無ければ Run を作らずに止める。既定値で黙って判定すると、意図しない基準で合否が出る
GATE_CONFIG="${COLLECTOR_DIR}/targets/${QG_REPOSITORY/\//__}.gate.yml"
[ -s "$GATE_CONFIG" ] || die "合格ラインがありません: $GATE_CONFIG"

API="${QG_BASE_URL%/}/api/v1/runs"
AUTH=(-H "Authorization: Bearer ${QG_INGEST_TOKEN}")
# curl の --retry は、一時的な障害（5xx など）を再試行する

# スキップの申告: 計測プロファイルの SKIP_METRICS と、measure.sh が書いた skipped-metrics.tsv（指標 ID<TAB>理由）
skipped_json() {
  local id reason
  {
    for id in ${SKIP_METRICS:-}; do
      jq -n --arg id "$id" '{metricId: $id, reason: "収集ランナーでは計測していないため"}'
    done
    if [ -f "$REPORTS/skipped-metrics.tsv" ]; then
      while IFS=$'\t' read -r id reason; do
        if [ -n "$id" ]; then
          jq -n --arg id "$id" --arg reason "$reason" '{metricId: $id, reason: $reason}'
        fi
      done < "$REPORTS/skipped-metrics.tsv"
    fi
  } | jq -s 'unique_by(.metricId)'
}

skipped() { [ -f "$REPORTS/skipped-metrics.tsv" ] && cut -f1 "$REPORTS/skipped-metrics.tsv" | grep -qx "$1"; }

REQUEST=$(jq -n \
  --arg repository "$QG_REPOSITORY" \
  --arg commitSha "$COMMIT_SHA" \
  --arg baseCommitSha "$BASE_SHA" \
  --arg branch "$BRANCH" \
  --arg pr "$PR_NUMBER" \
  --arg triggeredBy "${QG_TRIGGERED_BY:-collector}" \
  --arg ciRunUrl "${QG_CI_RUN_URL:-}" \
  --arg measuredAt "$(date -u +%FT%TZ)" \
  --arg tags "${TAGS:-}" \
  --argjson skippedMetrics "$(skipped_json)" \
  '{repository: $repository, commitSha: $commitSha, branch: $branch,
    triggeredBy: $triggeredBy, measuredAt: $measuredAt,
    tags: ($tags | split(" ") | map(select(. != ""))),
    skippedMetrics: $skippedMetrics}
   + (if $baseCommitSha != "" then {baseCommitSha: $baseCommitSha} else {} end)
   + (if $pr != "" then {pullRequestNumber: ($pr | tonumber)} else {} end)
   + (if $ciRunUrl != "" then {ciRunUrl: $ciRunUrl} else {} end)')

RUN_ID=$(curl -sS --retry 3 --fail-with-body -X POST "$API" "${AUTH[@]}" \
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
  curl -sS --retry 3 --fail-with-body -X POST "${API}/${RUN_ID}/artifacts?${query}" "${AUTH[@]}" "${args[@]}" >/dev/null
  echo "送信しました: type=$type ${component:+component=$component }${scope:+scope=$scope }${file#"$REPORTS"/}"
}

upload quality-gate-config "$GATE_CONFIG"
# ファイルの移動・リネーム。移動しただけのファイルの違反を新規・解消として扱わないために使われる
upload git-renames "$REPORTS/renames.json"

# コンポーネント名は計測プロファイルのディレクトリ名（backend / frontend）とする
BACKEND=${BACKEND_DIR##*/}
FRONTEND=${FRONTEND_DIR##*/}

if [ -n "${BACKEND_DIR:-}" ]; then
  upload jacoco-xml "$REPORTS/backend/jacoco.xml" "$BACKEND"
  # 収集ランナーの PIT は常に全量（変更範囲への絞り込みはしない）
  if [ -n "${MUTATION_TARGET_CLASSES:-}" ] && ! skipped M-02; then
    upload pit-xml "$REPORTS/backend/mutations.xml" "$BACKEND" '' '{"mutationScope":"all"}'
  fi
  upload pmd-xml "$REPORTS/backend/pmd.xml" "$BACKEND" head
  # base の解析結果があれば、M-07 は「新規・悪化した関数」を判定できる
  if [ -n "$BASE_SHA" ] && [ -s "$REPORTS/backend/pmd-base.xml" ]; then
    upload pmd-xml "$REPORTS/backend/pmd-base.xml" "$BACKEND" base
  fi
  # M-11 / M-12 はすべてのテストの結果（test-junit-xml）
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
  upload eslint-json "$REPORTS/frontend/eslint.json" "$FRONTEND" head
  if [ -n "$BASE_SHA" ] && [ -s "$REPORTS/frontend/eslint-base.json" ]; then
    upload eslint-json "$REPORTS/frontend/eslint-base.json" "$FRONTEND" base
  fi
fi
[ -z "${A11Y_PAGES:-}" ] || upload axe-json "$REPORTS/frontend/axe-results.json" "$FRONTEND"
if [ -n "${OPENAPI_PATH:-}" ]; then
  if [ -e "$REPORTS/oasdiff-base-spec-missing" ]; then
    upload oasdiff-json "$REPORTS/oasdiff.json" "$BACKEND" '' '{"baseSpecMissing":true}'
  else
    upload oasdiff-json "$REPORTS/oasdiff.json" "$BACKEND"
  fi
fi
# 走査した対象を申告する（申告の無い SARIF は、すべて M-06 として読まれる）
upload sarif "$REPORTS/trivy.sarif" '' '' '{"scanners":["vuln","secret"]}'
upload sarif "$REPORTS/trivy-license.sarif" '' '' '{"scanners":["license"]}'
# M-03〜05。1 ファイル = 1 回の実行。計測環境（と異常終了）は measure.sh が書いた .metadata を添える
if [ -n "${PERF_SCRIPT:-}" ] && ! skipped M-03; then
  found=0
  for summary in "$REPORTS"/perf/k6-summary-*.json; do
    [ -e "$summary" ] || continue
    upload k6-summary "$summary" "$BACKEND" '' "$(cat "$summary.metadata")"
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
[ "$STATUS" = "EVALUATED" ] || die "判定に失敗しました（$(echo "$RESULT" | jq -r '.errorCode')）。理由は Run 詳細を確認してください"
