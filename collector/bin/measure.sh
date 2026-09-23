#!/usr/bin/env bash
# 取得した対象リポジトリで計測し、成果物を reports/ にまとめる（段階 1: M-01 / M-06 / M-07 / M-08 / M-09）。
#
# 使い方: measure.sh <owner/name> <作業ディレクトリ> <reports ディレクトリ>
#   作業ディレクトリには fetch.sh の出力（src/ と meta.env）があること。
#
# 対象のテストコードを実行するため、このスクリプトには認証情報を渡さない。
# 指標ごとの失敗は警告にとどめて続行する。未提出の指標は quality-gate が ERROR として扱う。
#
# 必要なもの: git / curl / unzip / docker、JDK（バックエンド）、Node.js（フロントエンド）
# 環境変数:
#   QG_COLLECTOR_CACHE  PMD などを置くキャッシュ（既定: ~/.cache/quality-gate-collector）
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[ $# -eq 3 ] || die "使い方: measure.sh <owner/name> <作業ディレクトリ> <reports ディレクトリ>"
REPOSITORY=$1
WORK=$(cd "$2" && pwd)
mkdir -p "$3"
REPORTS=$(cd "$3" && pwd)
SRC="$WORK/src"
CACHE=${QG_COLLECTOR_CACHE:-$HOME/.cache/quality-gate-collector}

load_profile "$REPOSITORY"
load_env "$WORK/meta.env"
[ "$(git -C "$SRC" rev-parse HEAD)" = "$COMMIT_SHA" ] || die "作業ディレクトリのコミットが meta.env と一致しません"

FAILED=()
fail() { warn "$1"; FAILED+=("$1"); }

cp "$WORK/meta.env" "$REPORTS/meta.env"
cp "$COLLECTOR_DIR/versions.env" "$REPORTS/versions.env"
mkdir -p "$REPORTS/backend" "$REPORTS/contract"

# --- バックエンド: M-01（JaCoCo）/ M-08（JUnit XML） ----------------------------------
measure_backend() {
  local dir="$SRC/$BACKEND_DIR" mvn exec
  [ -f "$dir/pom.xml" ] || { fail "M-01/M-08: $BACKEND_DIR/pom.xml がありません"; return; }
  if [ -x "$dir/mvnw" ]; then mvn=(./mvnw); else mvn=(mvn); fi
  exec="$dir/target/qg-collector-jacoco.exec"

  group "バックエンドのビルドとテスト（JaCoCo ${JACOCO_VERSION}）"
  # JaCoCo はコマンドラインから差し込む（pom に設定が無くても計測できる）。
  # テストの失敗では止めない。失敗したテストも M-08 の判定材料として送る
  if ! (cd "$dir" && "${mvn[@]}" -B -ntp \
        "org.jacoco:jacoco-maven-plugin:${JACOCO_VERSION}:prepare-agent" \
        verify \
        "org.jacoco:jacoco-maven-plugin:${JACOCO_VERSION}:report" \
        -Djacoco.destFile="$exec" -Djacoco.dataFile="$exec" \
        -Dmaven.test.failure.ignore=true -Dpmd.skip=true -Dcpd.skip=true); then
    fail "バックエンドのビルドに失敗しました（M-01 Java / M-08 は送られません）"
  fi
  endgroup

  if [ -s "$dir/target/site/jacoco/jacoco.xml" ]; then
    cp "$dir/target/site/jacoco/jacoco.xml" "$REPORTS/backend/jacoco.xml"
  else
    fail "M-01: JaCoCo のレポートがありません"
  fi

  local copied=0 file
  for file in "$dir"/target/$CONTRACT_TEST_REPORTS; do
    [ -e "$file" ] || continue
    cp "$file" "$REPORTS/contract/"
    copied=$((copied + 1))
  done
  [ "$copied" -gt 0 ] || fail "M-08: 契約テストの結果がありません（target/$CONTRACT_TEST_REPORTS）"
}

# --- バックエンド: M-07（PMD）。head と base の両方を解析する -------------------------
pmd_bin() {
  local home="$CACHE/pmd-bin-${PMD_VERSION}"
  if [ ! -x "$home/bin/pmd" ]; then
    mkdir -p "$CACHE"
    curl -fsSL -o "$CACHE/pmd.zip" \
      "https://github.com/pmd/pmd/releases/download/pmd_releases%2F${PMD_VERSION}/pmd-dist-${PMD_VERSION}-bin.zip"
    rm -rf "$home"
    unzip -q -o "$CACHE/pmd.zip" -d "$CACHE"
    rm -f "$CACHE/pmd.zip"
  fi
  echo "$home/bin/pmd"
}

run_pmd() {
  local sources=$1 output=$2
  "$PMD" check --no-cache --no-progress --no-fail-on-violation \
    -R "$COLLECTOR_DIR/pmd-ruleset.xml" -f xml -d "$sources" -r "$output"
}

measure_complexity() {
  local sources="$SRC/$BACKEND_DIR/src/main/java"
  [ -d "$sources" ] || { fail "M-07: $BACKEND_DIR/src/main/java がありません"; return; }
  group "循環的複雑度（PMD ${PMD_VERSION}）"
  PMD=$(pmd_bin) || { fail "M-07: PMD を取得できませんでした"; endgroup; return; }
  run_pmd "$sources" "$REPORTS/backend/pmd.xml" || fail "M-07: PMD（head）の実行に失敗しました"

  # base は別の作業ツリーで解析する。PMD のパスは quality-gate がモジュール相対（src/...）に寄せるため、
  # 置き場所が違っても同じ関数として比較される
  if [ -n "$BASE_SHA" ]; then
    rm -rf "$WORK/base"
    git -C "$SRC" worktree add --quiet --detach "$WORK/base" "$BASE_SHA"
    if [ -d "$WORK/base/$BACKEND_DIR/src/main/java" ]; then
      run_pmd "$WORK/base/$BACKEND_DIR/src/main/java" "$REPORTS/backend/pmd-base.xml" \
        || fail "M-07: PMD（base）の実行に失敗しました"
    else
      log "base に $BACKEND_DIR/src/main/java が無いため、base の解析を省きます"
    fi
    git -C "$SRC" worktree remove --force "$WORK/base"
  fi
  endgroup
}

# --- フロントエンド: M-01（Vitest + v8 カバレッジ） -----------------------------------
measure_frontend() {
  local dir="$SRC/$FRONTEND_DIR" version args=() pattern
  [ -f "$dir/package.json" ] || { fail "M-01: $FRONTEND_DIR/package.json がありません"; return; }
  group "フロントエンドのテスト（Vitest / v8 カバレッジ）"
  (
    set -e
    cd "$dir"
    npm ci --no-audit --no-fund
    version=$(node -p "require('vitest/package.json').version")
    # カバレッジのプロバイダが対象に無ければ、Vitest と同じ版を作業用の clone にだけ入れる
    if ! node -e "require.resolve('@vitest/coverage-v8/package.json')" 2>/dev/null; then
      npm install --no-save --no-audit --no-fund "@vitest/coverage-v8@${version}"
    fi
    args=(--coverage.enabled=true --coverage.provider=v8 --coverage.reportOnFailure=true
          --coverage.reporter=lcovonly "--coverage.reportsDirectory=$REPORTS/frontend-coverage")
    # パターンは空白区切り（{ts,vue} のようにカンマを含むため）。read は glob を展開しない
    read -ra patterns <<< "${FRONTEND_COVERAGE_INCLUDE:-}"
    for pattern in "${patterns[@]}"; do args+=("--coverage.include=$pattern"); done
    read -ra patterns <<< "${FRONTEND_COVERAGE_EXCLUDE:-}"
    for pattern in "${patterns[@]}"; do args+=("--coverage.exclude=$pattern"); done
    # テストの失敗では止めない（カバレッジは reportOnFailure で出る）
    npx vitest run "${args[@]}" || echo "::warning::フロントエンドのテストに失敗があります"
  ) || fail "フロントエンドの計測に失敗しました（M-01 TS は送られません）"
  endgroup
  [ -s "$REPORTS/frontend-coverage/lcov.info" ] || fail "M-01: lcov.info がありません"
}

# --- M-09（oasdiff）。コミットされている OpenAPI 定義を head と base で比べる --------
measure_breaking_changes() {
  group "OpenAPI の破壊的変更（${OASDIFF_IMAGE}）"
  rm -f "$REPORTS/oasdiff-base-spec-missing"
  if ! git -C "$SRC" show "$COMMIT_SHA:$OPENAPI_PATH" > "$REPORTS/openapi-head.yml" 2>/dev/null; then
    rm -f "$REPORTS/openapi-head.yml"
    fail "M-09: head に $OPENAPI_PATH がありません"
    endgroup
    return
  fi
  rm -f "$REPORTS/openapi-base.yml"
  if [ -n "$BASE_SHA" ] && git -C "$SRC" cat-file -e "$BASE_SHA:$OPENAPI_PATH" 2>/dev/null; then
    git -C "$SRC" show "$BASE_SHA:$OPENAPI_PATH" > "$REPORTS/openapi-base.yml"
    if docker run --rm -v "$REPORTS:/w:ro" -w /w "$OASDIFF_IMAGE" \
         breaking openapi-base.yml openapi-head.yml --format json > "$REPORTS/oasdiff.json"; then
      # 変更が無いときの空出力は 0 件として送る
      [ -s "$REPORTS/oasdiff.json" ] || echo '[]' > "$REPORTS/oasdiff.json"
    else
      rm -f "$REPORTS/oasdiff.json"
      fail "M-09: oasdiff の実行に失敗しました"
    fi
  else
    # 比較元に定義が無い（新規 API）。quality-gate は M-09 を対象外として扱う
    echo '[]' > "$REPORTS/oasdiff.json"
    touch "$REPORTS/oasdiff-base-spec-missing"
  fi
  endgroup
}

# --- M-06（Trivy）。依存関係を取得した後の作業ツリーを走査する（対象の CI と同じ順序） ----
measure_vulnerabilities() {
  group "脆弱性スキャン（${TRIVY_IMAGE}）"
  # 出力は標準出力で受け取る（コンテナの root 権限で書かれたファイルを作業領域に残さない）
  if ! docker run --rm -v "$SRC:/src:ro" -w /src -v quality-gate-collector-trivy:/root/.cache/ \
       "$TRIVY_IMAGE" fs --quiet --format sarif --severity CRITICAL,HIGH,MEDIUM . > "$REPORTS/trivy.sarif"; then
    rm -f "$REPORTS/trivy.sarif"
    fail "M-06: Trivy の実行に失敗しました"
  fi
  endgroup
}

if [ -n "${BACKEND_DIR:-}" ]; then
  measure_backend
  measure_complexity
fi
[ -z "${FRONTEND_DIR:-}" ] || measure_frontend
[ -z "${OPENAPI_PATH:-}" ] || measure_breaking_changes
measure_vulnerabilities

log "計測結果:"
(cd "$REPORTS" && find . -type f ! -name '*.env' | sort | sed 's/^/  /') >&2
if [ ${#FAILED[@]} -gt 0 ]; then
  warn "計測できなかったものがあります（${#FAILED[@]} 件）。該当の指標は ERROR になります"
  printf '  - %s\n' "${FAILED[@]}" >&2
fi
