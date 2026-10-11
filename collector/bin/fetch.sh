#!/usr/bin/env bash
# 計測対象のリポジトリを取得し、計測するコミットと比較元（base）を決める。
#
# 使い方: fetch.sh <作業ディレクトリ>（計測対象は quality-gate の QG_REPOSITORY）
#
# 環境変数:
#   QG_BASE_URL / QG_INGEST_TOKEN
#                 quality-gate の URL と Ingest Token。アプリの設定を読み、計測対象のリポジトリ（QG_REPOSITORY）・
#                 判定しない指標（QG_DISABLED_METRICS）・M-08 で検査する画面（QG_ACCESSIBILITY_PAGES）を決める。
#                 計測プロファイルは QG_REPOSITORY が一致するもの（collector/targets/*/profile.env）を使う
#   QG_TARGET     計測プロファイルのディレクトリ名。QG_BASE_URL / QG_INGEST_TOKEN が無いとき（アプリなしで手元で試すとき）に使う。
#                 そのときは QG_DISABLED_METRICS / QG_ACCESSIBILITY_PAGES もこの環境変数から読む（カンマか空白区切り）
#   GH_TOKEN      clone に使うトークン（private リポジトリでは必須）。
#                 .git/config には書き込まない（後続の計測ジョブへ渡さないため）
#   QG_BRANCH     計測するブランチ（既定: 計測プロファイルの DEFAULT_BRANCH）
#   QG_COMMIT     計測するコミット（40 桁の SHA）またはタグ（既定: ブランチの先頭）
#
# 出力:
#   <作業ディレクトリ>/src       対象リポジトリ（全履歴。比較元・タグを求めるのと、比較元の OpenAPI 定義を読むのに使う）
#   <作業ディレクトリ>/meta.env  QG_TARGET（計測プロファイル）/ COMMIT_SHA / BRANCH / BASE_SHA / TAGS（コミットを指すタグ。空白区切り）/
#                                QG_DISABLED_METRICS（アプリの判定しない指標。空白区切り）/
#                                QG_ACCESSIBILITY_PAGES（アプリの M-08 で検査されているべき画面。空白区切り）
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[ $# -eq 1 ] || die "使い方: fetch.sh <作業ディレクトリ>"
WORK=$1

# アプリの設定。計測対象・判定しない指標・M-08 の画面はアプリの設定だけで決まる（計測プロファイルやワークフローには書かない）。
# 計測に時間をかける前に、設定の誤りはここで止める
if [ -n "${QG_BASE_URL:-}" ] && [ -n "${QG_INGEST_TOKEN:-}" ]; then
  read_app_settings
  if [ -z "${QG_TARGET:-}" ]; then
    QG_TARGET=$(find_target "$APP_REPOSITORY") || exit 1
    export QG_TARGET
  fi
  APP_DISABLED_METRICS=$(jq -r '.disabledMetrics | join(" ")' <<< "$SETTINGS")
  # 空白区切りで meta.env に書くため、空白と引用符を含む画面は受け付けない
  jq -e '.accessibilityPages | all(test("^[^\\s\"]+$"))' <<< "$SETTINGS" >/dev/null \
    || die "quality-gate の QG_ACCESSIBILITY_PAGES に空白か引用符を含む画面があります"
  APP_ACCESSIBILITY_PAGES=$(jq -r '.accessibilityPages | join(" ")' <<< "$SETTINGS")
else
  [ -n "${QG_TARGET:-}" ] || die "QG_BASE_URL と QG_INGEST_TOKEN（アプリの設定を読む）か、QG_TARGET（計測プロファイル）を指定してください"
  log "QG_BASE_URL / QG_INGEST_TOKEN が無いため、quality-gate の設定は読みません（計測しない指標と M-08 の画面は環境変数から読む）"
  APP_REPOSITORY=""
  # カンマか空白区切りの値を、空白 1 つ区切りにする
  words() { local list; read -ra list <<< "${1//,/ }"; echo "${list[*]}"; }
  APP_DISABLED_METRICS=$(words "${QG_DISABLED_METRICS:-}")
  APP_ACCESSIBILITY_PAGES=$(words "${QG_ACCESSIBILITY_PAGES:-}")
fi
[[ "$APP_DISABLED_METRICS" =~ ^(M-[0-9]{2}( M-[0-9]{2})*)?$ ]] \
  || die "判定しない指標（QG_DISABLED_METRICS）を解釈できません: $APP_DISABLED_METRICS"
[[ "$APP_ACCESSIBILITY_PAGES" != *[\"\']* ]] || die "QG_ACCESSIBILITY_PAGES に引用符を含む画面があります"

load_profile
REPOSITORY=$QG_REPOSITORY
[ -z "$APP_REPOSITORY" ] || [ "$APP_REPOSITORY" = "$REPOSITORY" ] \
  || die "計測プロファイル（$QG_TARGET）の QG_REPOSITORY（$REPOSITORY）が quality-gate の QG_REPOSITORY（$APP_REPOSITORY）と違います"
log "計測プロファイル: collector/targets/$QG_TARGET（$REPOSITORY）"
log "判定しない指標（QG_DISABLED_METRICS）: ${APP_DISABLED_METRICS:-なし}"
log "M-08 で検査されているべき画面（QG_ACCESSIBILITY_PAGES）: ${APP_ACCESSIBILITY_PAGES:-なし}"

BRANCH=${QG_BRANCH:-$DEFAULT_BRANCH}

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
"${GIT[@]}" clone --quiet --no-checkout "https://github.com/${REPOSITORY}.git" "$WORK/src"
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

# コミットを指すタグ。リリース判定（REL-1）でタグを指定したとき、quality-gate はこれでコミットを探す
TAGS=$(git tag --points-at "$COMMIT" | tr '\n' ' ' | sed 's/ *$//')

cat > "$WORK/meta.env" <<EOF
QG_TARGET=$QG_TARGET
QG_REPOSITORY=$REPOSITORY
COMMIT_SHA=$COMMIT
BRANCH=$BRANCH
BASE_SHA=$BASE
TAGS="$TAGS"
QG_DISABLED_METRICS="$APP_DISABLED_METRICS"
QG_ACCESSIBILITY_PAGES="$APP_ACCESSIBILITY_PAGES"
EOF
log "計測するコミット: $COMMIT${TAG:+（$TAG）}（比較元: ${BASE:-なし}${BASE_LABEL:+（$BASE_LABEL）}）"
