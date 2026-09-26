#!/usr/bin/env bash
# 計測対象のリポジトリを取得し、計測するコミットと比較元（base）を決める。
#
# 使い方: fetch.sh <owner/name> <作業ディレクトリ>
#
# 環境変数:
#   GH_TOKEN      clone に使うトークン（private リポジトリでは必須）。
#                 .git/config には書き込まない（後続の計測ジョブへ渡さないため）
#   QG_BRANCH     計測するブランチ（既定: 計測プロファイルの DEFAULT_BRANCH）
#   QG_COMMIT     計測するコミット（40 桁の SHA）またはタグ（既定: ブランチの先頭）
#   QG_PR_NUMBER  PR を計測する場合の番号。PR の先頭（refs/pull/<番号>/head）を計測する
#   QG_BASE_BRANCH 比較元を決めるブランチ（既定: DEFAULT_BRANCH）。PR ではマージ先のブランチ
#   QG_BASE       比較元のコミット（40 桁の SHA）またはタグ。指定すると下の自動の決め方より優先する
#   QG_REMOTE_URL clone 元の URL（既定: https://github.com/<owner/name>.git。試験用）
#
# 出力:
#   <作業ディレクトリ>/src       対象リポジトリ（全履歴。base の解析に必要）
#   <作業ディレクトリ>/meta.env  COMMIT_SHA / BRANCH / BASE_SHA / PR_NUMBER / TAGS（コミットを指すタグ。空白区切り）
#   <作業ディレクトリ>/renames.json  ファイルの移動・リネーム（renames.sh の出力）
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

# 40 桁の SHA か、タグ名として正しい文字列だけを受け付ける（git のコマンドにそのまま渡すため）
valid_ref() { [[ "$1" =~ ^[0-9a-f]{40}$ ]] || git check-ref-format "refs/tags/$1"; }
[ -z "${QG_COMMIT:-}" ] || valid_ref "$QG_COMMIT" || die "コミットは 40 桁の SHA かタグ名で指定してください: $QG_COMMIT"
[ -z "${QG_BASE:-}" ] || valid_ref "$QG_BASE" || die "比較元は 40 桁の SHA かタグ名で指定してください: $QG_BASE"

# SHA ならそのコミット、それ以外はタグが指すコミット（注釈付きタグもたどる）。見つからなければ何も出さない
resolve_commit() {
  if [[ "$1" =~ ^[0-9a-f]{40}$ ]]; then
    git cat-file -e "${1}^{commit}" 2>/dev/null && echo "$1"
  else
    git rev-parse --verify --quiet "refs/tags/${1}^{commit}"
  fi
  return 0
}

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
TAG=""
if [ -n "${QG_COMMIT:-}" ]; then
  [[ "$QG_COMMIT" =~ ^[0-9a-f]{40}$ ]] || TAG=$QG_COMMIT
  COMMIT=$(resolve_commit "$QG_COMMIT")
  [ -n "$COMMIT" ] || die "コミットまたはタグが見つかりません: $QG_COMMIT"
else
  COMMIT=$(git rev-parse "$HEAD_REF")
fi
git checkout --quiet --detach "$COMMIT"

# 比較元（新規の違反・破壊的変更・スキップの増加を数える起点）。
#   - 指定（QG_BASE）があればそれ
#   - タグを計測するときは、その前のタグ（リリース判定では「前回のリリースから何が増えたか」を見るため）。
#     前のタグが無ければ直前のコミット
#   - 既定ブランチ上の計測なら直前のコミット、それ以外は比較先のブランチ（PR ならマージ先）との merge-base。
#     対象リポジトリの CI（push なら HEAD~1、PR なら merge-base）と同じ決め方にする
BASE_LABEL=""
if [ -n "${QG_BASE:-}" ]; then
  BASE=$(resolve_commit "$QG_BASE")
  [ -n "$BASE" ] || die "比較元のコミットまたはタグが見つかりません: $QG_BASE"
  [ "$BASE" != "$COMMIT" ] || die "比較元が計測するコミットと同じです: $QG_BASE"
  BASE_LABEL=$QG_BASE
elif [ -z "$PR_NUMBER" ] && [ "$BRANCH" = "$BASE_BRANCH" ]; then
  BASE=""
  if [ -n "$TAG" ]; then
    BASE_LABEL=$(git describe --tags --abbrev=0 "${COMMIT}~1" 2>/dev/null || true)
    [ -z "$BASE_LABEL" ] || BASE=$(git rev-parse --verify --quiet "refs/tags/${BASE_LABEL}^{commit}" || true)
  fi
  [ -n "$BASE" ] || BASE=$(git rev-parse --verify --quiet "${COMMIT}~1" || true)
else
  BASE=$(git merge-base "$COMMIT" "origin/${BASE_BRANCH}" || true)
  [ "$BASE" != "$COMMIT" ] || BASE=$(git rev-parse --verify --quiet "${COMMIT}~1" || true)
fi

# コミットを指すタグ。リリース判定（S-09）でタグを指定したとき、quality-gate はこれでコミットを探す
TAGS=$(git tag --points-at "$COMMIT" | tr '\n' ' ' | sed 's/ *$//')

cat > "$WORK/meta.env" <<EOF
QG_REPOSITORY=$REPOSITORY
COMMIT_SHA=$COMMIT
BRANCH=$BRANCH
BASE_SHA=$BASE
PR_NUMBER=$PR_NUMBER
TAGS="$TAGS"
EOF
"$COLLECTOR_DIR/bin/renames.sh" "$WORK/src" "$COMMIT" "$BASE" > "$WORK/renames.json"
log "計測するコミット: $COMMIT${TAG:+（$TAG）}（比較元: ${BASE:-なし}${BASE_LABEL:+（$BASE_LABEL）}）"
