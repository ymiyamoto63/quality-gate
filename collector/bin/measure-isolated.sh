#!/usr/bin/env bash
# measure.sh をコンテナの中で実行する（計測のコンテナ隔離）。
#
# 使い方: measure-isolated.sh <owner/name> <作業ディレクトリ> <reports ディレクトリ>（measure.sh と同じ）
#
# 対象のビルド・テストのコードはコンテナの中だけで動く。コンテナに見せるのは次のものだけ:
#   - 作業ディレクトリと reports ディレクトリ（読み書き）
#   - collector/（読み取りのみ）
#   - キャッシュ用のボリューム（Maven・npm・PMD・Trivy の DB）
# ランナーのマシンの他のファイル、Docker のソケット、他のジョブの作業領域には触れられない。
# 権限は落とし（--cap-drop ALL、no-new-privileges）、ランナーの利用者の UID で動かす。
#
# 計測プロファイルで ISOLATION=none にすると、コンテナを使わずに measure.sh を直接実行する。
#
# 環境変数:
#   QG_COLLECTOR_BASE_IMAGE  ベースのイメージ（既定: eclipse-temurin:<JAVA_VERSION>-jdk-noble）
#   QG_COLLECTOR_MEMORY      コンテナのメモリ上限（既定: 6g）
#   QG_COLLECTOR_CPUS        コンテナの CPU 上限（既定: 制限しない）
#   QG_COLLECTOR_DOCKER_ARGS docker run に足す引数（空白区切り）。社内のプロキシを通す場合などに使う
#                            （例: --env HTTPS_PROXY --env JAVA_TOOL_OPTIONS）
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[ $# -eq 3 ] || die "使い方: measure-isolated.sh <owner/name> <作業ディレクトリ> <reports ディレクトリ>"
REPOSITORY=$1
WORK=$(cd "$2" && pwd)
mkdir -p "$3"
REPORTS=$(cd "$3" && pwd)

load_profile "$REPOSITORY"
case "${ISOLATION:-container}" in
  none)
    log "計測プロファイルの ISOLATION=none のため、コンテナを使わずに計測します"
    exec "$COLLECTOR_DIR/bin/measure.sh" "$@"
    ;;
  container) ;;
  *) die "ISOLATION は container か none を指定してください: $ISOLATION" ;;
esac
command -v docker >/dev/null || die "docker がありません（ISOLATION=none にすればコンテナを使わずに計測できます）"

# Node.js の版を完全な版（22.21.1 など）に解決する。.nvmrc には 22 や v22 や lts/* と書かれうる
node_version() {
  local wanted=22 file="$WORK/src/${NODE_VERSION_FILE:-}"
  if [ -n "${NODE_VERSION_FILE:-}" ] && [ -f "$file" ]; then
    wanted=$(tr -d '[:space:]' < "$file")
  fi
  wanted=${wanted#v}
  curl -fsSL https://nodejs.org/dist/index.json | jq -r --arg w "$wanted" '
    [.[] | select(
      if $w == "lts/*" or $w == "lts" or $w == "node" then (.lts != false or $w == "node")
      else ((.version | ltrimstr("v")) == $w or (.version | ltrimstr("v") | startswith($w + ".")))
      end)][0].version // empty' | sed 's/^v//'
}

JAVA=${JAVA_VERSION:-21}
NODE=$(node_version) || true
[ -n "$NODE" ] || die "Node.js の版を解決できませんでした（${NODE_VERSION_FILE:-既定の 22}）"
TRIVY_VERSION=${TRIVY_IMAGE##*:}
OASDIFF_VERSION=${OASDIFF_IMAGE##*:v}
BASE_IMAGE=${QG_COLLECTOR_BASE_IMAGE:-eclipse-temurin:${JAVA}-jdk-noble}
# ベースのイメージ・Dockerfile・検査ツールの内容でタグを決める。同じなら作り直さない
HASH=$({ echo "$BASE_IMAGE"; cat "$COLLECTOR_DIR/runner/Dockerfile" "$COLLECTOR_DIR/a11y/package-lock.json" \
  "$COLLECTOR_DIR/complexity/package-lock.json" "$COLLECTOR_DIR/jscpd/package-lock.json" \
  "$COLLECTOR_DIR/lighthouse/package-lock.json"; } \
  | sha256sum | cut -c1-12)
IMAGE="quality-gate-collector:java${JAVA}-node${NODE}-maven${MAVEN_VERSION}-trivy${TRIVY_VERSION}-oasdiff${OASDIFF_VERSION}-${HASH}"

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  group "計測用のコンテナの作成（${IMAGE}）"
  docker build -f "$COLLECTOR_DIR/runner/Dockerfile" -t "$IMAGE" \
    --build-arg "BASE_IMAGE=$BASE_IMAGE" --build-arg "NODE_VERSION=$NODE" \
    --build-arg "MAVEN_VERSION=$MAVEN_VERSION" \
    --build-arg "TRIVY_VERSION=$TRIVY_VERSION" --build-arg "OASDIFF_VERSION=$OASDIFF_VERSION" \
    "$COLLECTOR_DIR"
  endgroup
fi

limits=(--memory "${QG_COLLECTOR_MEMORY:-6g}" --pids-limit 4096)
[ -z "${QG_COLLECTOR_CPUS:-}" ] || limits+=(--cpus "$QG_COLLECTOR_CPUS")
read -ra extra <<< "${QG_COLLECTOR_DOCKER_ARGS:-}"

# キャッシュのボリュームは、どの UID でも書けるようにしておく（ボリュームが別の経路で作られていた場合に備える）。
# 対象のコードは動かさないので、ここだけは root で動かす
docker run --rm --user 0 --cap-drop ALL --cap-add FOWNER --network none \
  -v quality-gate-collector-home:/cache/home "$IMAGE" chmod 1777 /cache/home

log "コンテナの中で計測します（${IMAGE}）"
# 作業領域はホストと同じパスで見せる（meta.env などに書かれたパスをそのまま使えるように）
docker run --rm --init \
  --user "$(id -u):$(id -g)" \
  --cap-drop ALL --security-opt no-new-privileges \
  "${limits[@]}" "${extra[@]}" \
  -v "$WORK:$WORK" -v "$REPORTS:$REPORTS" \
  -v "$COLLECTOR_DIR:$COLLECTOR_DIR:ro" \
  -v quality-gate-collector-home:/cache/home \
  -w "$WORK" \
  "$IMAGE" "$COLLECTOR_DIR/bin/measure.sh" "$REPOSITORY" "$WORK" "$REPORTS"
