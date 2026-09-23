#!/usr/bin/env bash
# 計測対象のリポジトリを取得し、計測するコミットと比較元（base）を決める。
#
# 使い方: fetch.sh <owner/name> <作業ディレクトリ>
#
# 環境変数:
#   GH_TOKEN      clone に使うトークン（private リポジトリでは必須）。
#                 .git/config には書き込まない（後続の計測ジョブへ渡さないため）
#   QG_BRANCH     計測するブランチ（既定: 計測プロファイルの DEFAULT_BRANCH）
#   QG_COMMIT     計測するコミット（40 桁。既定: ブランチの先頭）
#   QG_PR_NUMBER  PR を計測する場合の番号。PR の先頭（refs/pull/<番号>/head）を計測する
#   QG_BASE_BRANCH 比較元を決めるブランチ（既定: DEFAULT_BRANCH）。PR ではマージ先のブランチ
#   QG_REMOTE_URL clone 元の URL（既定: https://github.com/<owner/name>.git。試験用）
#
# 出力:
#   <作業ディレクトリ>/src       対象リポジトリ（全履歴。base の解析に必要）
#   <作業ディレクトリ>/meta.env  COMMIT_SHA / BRANCH / BASE_SHA / PR_NUMBER
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[ $# -eq 2 ] || die "使い方: fetch.sh <owner/name> <作業ディレクトリ>"
REPOSITORY=$1
WORK=$2
load_profile "$REPOSITORY"

BRANCH=${QG_BRANCH:-$DEFAULT_BRANCH}
BASE_BRANCH=${QG_BASE_BRANCH:-$DEFAULT_BRANCH}
PR_NUMBER=${QG_PR_NUMBER:-}
REMOTE=${QG_REMOTE_URL:-https://github.com/${REPOSITORY}.git}
[ -z "$PR_NUMBER" ] || [[ "$PR_NUMBER" =~ ^[0-9]+$ ]] || die "PR 番号が不正です: $PR_NUMBER"
[ -z "${QG_COMMIT:-}" ] || [[ "$QG_COMMIT" =~ ^[0-9a-f]{40}$ ]] || die "コミットは 40 桁の SHA で指定してください: $QG_COMMIT"

GIT=(git)
if [ -n "${GH_TOKEN:-}" ]; then
  # トークンは -c で渡し、リモート URL にも設定ファイルにも残さない
  BASIC=$(printf 'x-access-token:%s' "$GH_TOKEN" | base64 | tr -d '\n')
  echo "::add-mask::$BASIC"
  GIT=(git -c "http.extraHeader=Authorization: Basic $BASIC")
fi

rm -rf "$WORK/src"
mkdir -p "$WORK"
log "取得します: $REPOSITORY（branch=$BRANCH${PR_NUMBER:+ pr=$PR_NUMBER}）"
"${GIT[@]}" clone --quiet --no-checkout "$REMOTE" "$WORK/src"
cd "$WORK/src"

if [ -n "$PR_NUMBER" ]; then
  "${GIT[@]}" fetch --quiet origin "+refs/pull/${PR_NUMBER}/head:refs/remotes/origin/pr/${PR_NUMBER}"
  HEAD_REF="origin/pr/${PR_NUMBER}"
else
  HEAD_REF="origin/${BRANCH}"
fi
COMMIT=${QG_COMMIT:-$(git rev-parse "$HEAD_REF")}
git cat-file -e "${COMMIT}^{commit}" 2>/dev/null || die "コミットが見つかりません: $COMMIT"
git checkout --quiet --detach "$COMMIT"

# 比較元: 既定ブランチ上の計測なら直前のコミット、それ以外は比較先のブランチ（PR ならマージ先）との merge-base。
# 対象リポジトリの CI（push なら HEAD~1、PR なら merge-base）と同じ決め方にする
if [ -z "$PR_NUMBER" ] && [ "$BRANCH" = "$BASE_BRANCH" ]; then
  BASE=$(git rev-parse --verify --quiet "${COMMIT}~1" || true)
else
  BASE=$(git merge-base "$COMMIT" "origin/${BASE_BRANCH}" || true)
  [ "$BASE" != "$COMMIT" ] || BASE=$(git rev-parse --verify --quiet "${COMMIT}~1" || true)
fi

cat > "$WORK/meta.env" <<EOF
QG_REPOSITORY=$REPOSITORY
COMMIT_SHA=$COMMIT
BRANCH=$BRANCH
BASE_SHA=$BASE
PR_NUMBER=$PR_NUMBER
EOF
log "計測するコミット: $COMMIT（base: ${BASE:-なし}）"
