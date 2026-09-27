#!/usr/bin/env bash
# 計測対象のリポジトリを取得し、計測するコミットと比較元（base）を決める。
#
# 使い方: fetch.sh <作業ディレクトリ>（計測対象は計測プロファイルの QG_REPOSITORY）
#
# 環境変数:
#   GH_TOKEN      clone に使うトークン（private リポジトリでは必須）。
#                 .git/config には書き込まない（後続の計測ジョブへ渡さないため）
#   QG_BRANCH     計測するブランチ（既定: 計測プロファイルの DEFAULT_BRANCH）
#   QG_COMMIT     計測するコミット（40 桁の SHA）またはタグ（既定: ブランチの先頭）
#   QG_REMOTE_URL clone 元の URL（既定: https://github.com/<owner/name>.git。試験用）
#
# 出力:
#   <作業ディレクトリ>/src       対象リポジトリ（全履歴。比較元・タグを求めるのと、比較元の OpenAPI 定義を読むのに使う）
#   <作業ディレクトリ>/meta.env  COMMIT_SHA / BRANCH / BASE_SHA / TAGS（コミットを指すタグ。空白区切り）
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[ $# -eq 1 ] || die "使い方: fetch.sh <作業ディレクトリ>"
WORK=$1
load_profile
REPOSITORY=$QG_REPOSITORY

BRANCH=${QG_BRANCH:-$DEFAULT_BRANCH}
REMOTE=${QG_REMOTE_URL:-https://github.com/${REPOSITORY}.git}

# 40 桁の SHA か、タグ名として正しい文字列だけを受け付ける（git のコマンドにそのまま渡すため）
valid_ref() { [[ "$1" =~ ^[0-9a-f]{40}$ ]] || git check-ref-format "refs/tags/$1"; }
[ -z "${QG_COMMIT:-}" ] || valid_ref "$QG_COMMIT" || die "コミットは 40 桁の SHA かタグ名で指定してください: $QG_COMMIT"

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
log "取得します: $REPOSITORY（branch=$BRANCH）"
"${GIT[@]}" clone --quiet --no-checkout "$REMOTE" "$WORK/src"
cd "$WORK/src"

HEAD_REF="origin/${BRANCH}"
TAG=""
if [ -n "${QG_COMMIT:-}" ]; then
  [[ "$QG_COMMIT" =~ ^[0-9a-f]{40}$ ]] || TAG=$QG_COMMIT
  COMMIT=$(resolve_commit "$QG_COMMIT")
  [ -n "$COMMIT" ] || die "コミットまたはタグが見つかりません: $QG_COMMIT"
else
  COMMIT=$(git rev-parse "$HEAD_REF")
fi
git checkout --quiet --detach "$COMMIT"

# 比較元（破壊的変更・スキップの増加を数える起点）。
#   - タグを計測するときは、その前のタグ（リリース判定では「前回のリリースから何が増えたか」を見るため）。
#     前のタグが無ければ直前のコミット
#   - 既定ブランチ上の計測なら直前のコミット、それ以外（別のブランチ）は既定ブランチとの merge-base
BASE_LABEL=""
if [ "$BRANCH" = "$DEFAULT_BRANCH" ]; then
  BASE=""
  if [ -n "$TAG" ]; then
    BASE_LABEL=$(git describe --tags --abbrev=0 "${COMMIT}~1" 2>/dev/null || true)
    [ -z "$BASE_LABEL" ] || BASE=$(git rev-parse --verify --quiet "refs/tags/${BASE_LABEL}^{commit}" || true)
  fi
  [ -n "$BASE" ] || BASE=$(git rev-parse --verify --quiet "${COMMIT}~1" || true)
else
  BASE=$(git merge-base "$COMMIT" "origin/${DEFAULT_BRANCH}" || true)
  [ "$BASE" != "$COMMIT" ] || BASE=$(git rev-parse --verify --quiet "${COMMIT}~1" || true)
fi

# コミットを指すタグ。リリース判定（S-08）でタグを指定したとき、quality-gate はこれでコミットを探す
TAGS=$(git tag --points-at "$COMMIT" | tr '\n' ' ' | sed 's/ *$//')

cat > "$WORK/meta.env" <<EOF
QG_REPOSITORY=$REPOSITORY
COMMIT_SHA=$COMMIT
BRANCH=$BRANCH
BASE_SHA=$BASE
TAGS="$TAGS"
EOF
log "計測するコミット: $COMMIT${TAG:+（$TAG）}（比較元: ${BASE:-なし}${BASE_LABEL:+（$BASE_LABEL）}）"
