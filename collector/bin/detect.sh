#!/usr/bin/env bash
# 定期実行で計測するコミットを探す（段階 2）。
#
# 計測プロファイルで SCHEDULE=true にした対象について、次の先頭コミットのうち未計測のものを選ぶ。
#   - 既定ブランチの先頭
#   - 同じリポジトリのブランチから出ている open な PR の先頭（MEASURE_PULL_REQUESTS=true のとき）
# フォークからの PR は計測しない（他人のコードをセルフホストランナーで実行しないため）。
#
# 使い方: detect.sh   → 計測する対象を JSON 配列で標準出力に出す（ログは標準エラー）
#
# 環境変数:
#   GH_TOKEN                    GitHub API のトークン（private リポジトリでは必須）
#   QG_OWNER                    GH_TOKEN が有効な owner。これと違う owner の対象は飛ばす
#   QG_COLLECTOR_MAX_PER_RUN    1 回で計測する最大件数（既定: 5）。残りは次回に回る
#   QG_COLLECTOR_MAX_FAILURES   この回数失敗したコミットは諦める（既定: 3）
#   QG_GITHUB_API               GitHub API の URL（既定: https://api.github.com。試験用）
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/state.sh"

API=${QG_GITHUB_API:-https://api.github.com}
MAX_PER_RUN=${QG_COLLECTOR_MAX_PER_RUN:-5}
MAX_FAILURES=${QG_COLLECTOR_MAX_FAILURES:-3}

github() {
  local path=$1 accept=${2:-application/vnd.github+json}
  local args=(-fsS -H "Accept: $accept" -H "X-GitHub-Api-Version: 2022-11-28")
  [ -z "${GH_TOKEN:-}" ] || args+=(-H "Authorization: Bearer $GH_TOKEN")
  curl "${args[@]}" "$API$path"
}

# 1 つの対象について候補を JSON Lines で出す
candidates_of() {
  local profile=$1
  (
    load_env "$COLLECTOR_DIR/versions.env"
    load_env "$profile"
    [ "${SCHEDULE:-false}" = "true" ] || exit 0
    local repository=$QG_REPOSITORY owner=${QG_REPOSITORY%%/*} head prs
    if [ -n "${GH_TOKEN:-}" ] && [ -n "${QG_OWNER:-}" ] && [ "$owner" != "$QG_OWNER" ]; then
      warn "$repository: トークンの owner（$QG_OWNER）と違うため飛ばします"
      exit 0
    fi

    if head=$(github "/repos/$repository/commits/$(jq -rn --arg b "$DEFAULT_BRANCH" '$b|@uri')" \
                application/vnd.github.sha); then
      jq -cn --arg r "$repository" --arg b "$DEFAULT_BRANCH" --arg c "$head" \
        '{repository: $r, branch: $b, commit: $c, pull_request: "", base_branch: $b}'
    else
      warn "$repository: 既定ブランチ $DEFAULT_BRANCH の先頭を取得できませんでした"
    fi

    [ "${MEASURE_PULL_REQUESTS:-false}" = "true" ] || exit 0
    if prs=$(github "/repos/$repository/pulls?state=open&per_page=100"); then
      jq -c --arg r "$repository" '.[]
        | select(.head.repo.full_name == $r)
        | {repository: $r, branch: .head.ref, commit: .head.sha,
           pull_request: (.number | tostring), base_branch: .base.ref}' <<< "$prs"
    else
      warn "$repository: PR の一覧を取得できませんでした（Pull requests の読み取り権限を確認してください）"
    fi
  )
}

selected=()
deferred=0
for profile in "$COLLECTOR_DIR"/targets/*.env; do
  [ -e "$profile" ] || continue
  while IFS= read -r candidate; do
    [ -n "$candidate" ] || continue
    key=$(state_key "$(jq -r .repository <<< "$candidate")" "$(jq -r .branch <<< "$candidate")" \
                    "$(jq -r .pull_request <<< "$candidate")" "$(jq -r .commit <<< "$candidate")")
    status=$(state_check "$key")
    case "$status" in
      ok) continue ;;
      failed*)
        if [ "${status#failed }" -ge "$MAX_FAILURES" ]; then
          warn "$key: ${status#failed } 回失敗しているため計測しません（手動実行で再計測できます）"
          continue
        fi ;;
    esac
    if [ ${#selected[@]} -ge "$MAX_PER_RUN" ]; then
      log "上限（$MAX_PER_RUN 件）に達したため次回に回します: $key"
      deferred=$((deferred + 1))
      continue
    fi
    log "計測します: $key（$status）"
    selected+=("$candidate")
  done < <(candidates_of "$profile")
done

if [ ${#selected[@]} -eq 0 ]; then
  if [ "$deferred" -eq 0 ]; then log "未計測のコミットはありません"; fi
  echo '[]'
else
  printf '%s\n' "${selected[@]}" | jq -cs .
fi
