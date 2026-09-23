#!/usr/bin/env bash
# quality-gate-action の本体。action.yml の入力と GitHub Actions の文脈から cli/qg-submit の引数を組み立てる。
set -euo pipefail

CLI="$GITHUB_ACTION_PATH/../cli/qg-submit"
args=(--repository "$GITHUB_REPOSITORY" --commit "$QG_HEAD_SHA" --branch "$QG_BRANCH"
      --triggered-by "$GITHUB_EVENT_NAME" --ci-run-url "$QG_RUN_URL" --github-output "$GITHUB_OUTPUT")

# ランナー種別。性能指標を判定に使うかどうかがこれで決まる（D-13）
runner_type=${QG_RUNNER_TYPE:-${RUNNER_ENVIRONMENT:-self-hosted}}
args+=(--runner-type "$runner_type")

# 比較元。PR ではマージ先との merge-base、push では直前のコミット。
# 履歴が足りず merge-base を求められなければ送らない（quality-gate がベース比較不可として扱う）
base=$QG_BASE_COMMIT
if [ -z "$base" ] && [ -n "$QG_PR_BASE_SHA" ]; then
  base=$(git merge-base "$QG_PR_BASE_SHA" "$QG_HEAD_SHA" 2>/dev/null || true)
  [ -n "$base" ] || echo "::notice::merge-base を求められないため比較元を送りません（actions/checkout に fetch-depth: 0 を指定してください）"
elif [ -z "$base" ] && [[ "${QG_PUSH_BEFORE:-}" =~ ^[0-9a-f]{40}$ ]] && [[ ! "$QG_PUSH_BEFORE" =~ ^0+$ ]]; then
  base=$QG_PUSH_BEFORE
fi
[ -z "$base" ] || args+=(--base-commit "$base")
[ -z "$QG_PR_NUMBER" ] || args+=(--pull-request "$QG_PR_NUMBER")

if [ -n "$QG_CONFIG" ]; then
  if [ -f "$QG_CONFIG" ]; then
    args+=(--config "$QG_CONFIG")
  else
    echo "::warning::設定ファイルがありません（$QG_CONFIG）。画面で保存した設定で判定されます"
  fi
fi

# 成果物: 1 行に 1 つ「type パス [component=..] [scope=..] [metadata=..]」
while read -r type path rest; do
  case "$type" in ''|'#'*) continue ;; esac
  [ -n "$path" ] || { echo "::error::成果物の行にパスがありません: $type"; exit 2; }
  args+=(--artifact "$type=$path")
  set -f   # metadata の JSON などをグロブとして展開しない
  for option in $rest; do
    case "$option" in
      component=*) args+=(--component "${option#component=}") ;;
      scope=*) args+=(--scope "${option#scope=}") ;;
      metadata=*) args+=(--metadata "${option#metadata=}") ;;
      *) echo "::error::成果物の行の解釈できない指定です: $option"; exit 2 ;;
    esac
  done
  set +f
done <<< "$QG_ARTIFACTS"

# スキップの申告: 1 行に 1 つ「指標 ID 理由」
while read -r id reason; do
  case "$id" in ''|'#'*) continue ;; esac
  args+=(--skip "$id=${reason:-CI で計測していないため}")
done <<< "$QG_SKIPPED"

[ "$QG_ALLOW_MISSING" != true ] || args+=(--allow-missing)
[ "${QG_WAIT:-0}" = 0 ] || args+=(--wait "$QG_WAIT")

if "$CLI" "${args[@]}"; then
  exit 0
else
  code=$?
fi
if [ "$QG_FAIL_ON_ERROR" = true ]; then
  exit "$code"
fi
# quality-gate の障害で CI を止めない（NFR 10.3）。失敗は警告として残す
echo "::warning::quality-gate への送信に失敗しました（終了コード $code）。CI は続行します（fail-on-error: false）"
