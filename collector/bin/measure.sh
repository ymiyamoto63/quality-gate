#!/usr/bin/env bash
# 取得した対象リポジトリで計測し、成果物を reports/ にまとめる（M-01 / M-02 / M-06 / M-07 / M-08 / M-09 / M-10）。
#
# 使い方: measure.sh <owner/name> <作業ディレクトリ> <reports ディレクトリ>
#   作業ディレクトリには fetch.sh の出力（src/ と meta.env）があること。
#
# 対象のテストコードを実行するため、このスクリプトには認証情報を渡さない。
# 指標ごとの失敗は警告にとどめて続行する。未提出の指標は quality-gate が ERROR として扱う。
# 計測しない指標とその理由は reports/skipped-metrics.tsv に書き、submit.sh がスキップとして申告する。
#
# 通常は measure-isolated.sh からコンテナの中で実行される（collector/runner/Dockerfile に必要なものがそろっている）。
# コンテナの外で直接実行するときに必要なもの:
#   git / curl / unzip / docker、JDK（バックエンド）、Node.js（フロントエンド）、Chromium の動作に必要なライブラリ（M-10）
# 環境変数:
#   QG_COLLECTOR_CACHE  PMD などを置くキャッシュ（既定: ~/.cache/quality-gate-collector）
#   A11Y_CHROMIUM       M-10 に使う Chromium の実行ファイル（任意。未指定なら Playwright が取得する）
#   QG_COLLECTOR_IN_CONTAINER  1 なら Trivy / oasdiff を Docker ではなくコンテナに入れたバイナリで実行する
#   QG_A11Y_TOOL_DIR    M-10 の検査ツールを取得済みのディレクトリ（コンテナのイメージに入っているもの）
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
# skip <指標 ID> <理由>
skip() { printf '%s\t%s\n' "$1" "$2" >> "$REPORTS/skipped-metrics.tsv"; log "$1 は計測しません: $2"; }

cp "$WORK/meta.env" "$REPORTS/meta.env"
cp "$COLLECTOR_DIR/versions.env" "$REPORTS/versions.env"
mkdir -p "$REPORTS/backend" "$REPORTS/contract" "$REPORTS/frontend"
rm -f "$REPORTS/skipped-metrics.tsv"

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

# --- バックエンド: M-02（PIT）。既定ブランチの計測でだけ全量を実行する -----------------
# PIT のコマンドライン版を、対象のテストのクラスパスで動かす（対象の pom は書き換えない）。
# measure_backend のビルドで出来た target/classes と target/test-classes をそのまま使う。
# 時間がかかるため、PR などの計測では実行せずスキップを申告する（Q-6、D-16）
measure_mutation() {
  local dir="$SRC/$BACKEND_DIR" out="$WORK/pit" mvn platform
  if [ -n "$PR_NUMBER" ] || [ "$BRANCH" != "$DEFAULT_BRANCH" ]; then
    skip M-02 "収集ランナーは既定ブランチ（$DEFAULT_BRANCH）の計測でだけミューテーションテストを実行する"
    return
  fi
  if [ -x "$dir/mvnw" ]; then mvn=(./mvnw); else mvn=(mvn); fi
  group "ミューテーションテスト（PIT ${PITEST_VERSION}、全量）"
  rm -rf "$out"
  mkdir -p "$out"
  # 失敗はサブシェルの終了コードで受け取る（|| の左側では set -e が効かないため、各行で抜ける）
  (
    cd "$dir" || exit 1
    # 対象のテストのクラスパス（test スコープまで）
    "${mvn[@]}" -B -q -ntp dependency:build-classpath -Dmdep.includeScope=test \
      -Dmdep.outputFile="$out/project-cp.txt" || exit 1
    # pitest-junit5-plugin が使う junit-platform-launcher は、対象の JUnit Platform と同じ版にそろえる
    platform=$(tr ':' '\n' < "$out/project-cp.txt" \
      | sed -n 's|.*/junit-platform-engine-\([^/]*\)\.jar$|\1|p' | head -n 1)
    [ -n "$platform" ] || { warn "M-02: テストの依存に JUnit Platform（JUnit 5）がありません"; exit 1; }
    "${mvn[@]}" -B -q -ntp -f "$COLLECTOR_DIR/pit/pom.xml" dependency:build-classpath \
      -Dpitest.version="$PITEST_VERSION" -Dpitest-junit5.version="$PITEST_JUNIT5_VERSION" \
      -Djunit-platform.version="$platform" -Dmdep.outputFile="$out/tools-cp.txt" || exit 1
    # PIT がテストを動かすクラスパス（1 行 1 件）。出力ファイルは末尾に改行が無いため echo で区切る
    {
      echo "$dir/target/classes"
      echo "$dir/target/test-classes"
      tr ':' '\n' < "$out/project-cp.txt"; echo
      tr ':' '\n' < "$out/tools-cp.txt" | grep -E '/(pitest-junit5-plugin|junit-platform-launcher)-[^/]*\.jar$'
    } | grep -v '^$' > "$out/minion-cp.txt"
    java -cp "$(cat "$out/tools-cp.txt")" org.pitest.mutationtest.commandline.MutationCoverageReport \
      --reportDir "$out/report" --outputFormats XML --timestampedReports=false \
      --targetClasses "$(csv "$MUTATION_TARGET_CLASSES")" \
      --targetTests "$(csv "${MUTATION_TARGET_TESTS:-$MUTATION_TARGET_CLASSES}")" \
      ${MUTATION_EXCLUDED_CLASSES:+--excludedClasses "$(csv "$MUTATION_EXCLUDED_CLASSES")"} \
      ${MUTATION_EXCLUDED_TESTS:+--excludedTestClasses "$(csv "$MUTATION_EXCLUDED_TESTS")"} \
      --sourceDirs "$dir/src/main/java" --mutableCodePaths "$dir/target/classes" \
      --classPathFile "$out/minion-cp.txt" --threads "${MUTATION_THREADS:-2}"
  ) || fail "M-02: PIT の実行に失敗しました（テストが失敗していると PIT は動きません）"
  endgroup
  if [ -s "$out/report/mutations.xml" ]; then
    cp "$out/report/mutations.xml" "$REPORTS/backend/mutations.xml"
  else
    fail "M-02: PIT のレポート（mutations.xml）がありません"
  fi
  rm -rf "$out"
}

# 空白区切りの並びをカンマ区切りにする（PIT の引数の形式）
csv() { local items; read -ra items <<< "$1"; (IFS=,; echo "${items[*]}"); }

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

# --- M-10（Playwright + axe-core）。対象アプリを起動して、計測プロファイルの画面を検査する ----
# 対象の e2e（API のモック）は使わず、バックエンドの jar とビルドした画面を実際に起動する。
# 検査のスクリプトとツールの版は quality-gate 側のもの（collector/a11y）
SERVERS=()
stop_servers() {
  local pid
  for pid in "${SERVERS[@]}"; do
    # setsid で起動したので、npx などの子プロセスごとプロセスグループで止める
    kill -- "-$pid" 2>/dev/null || true
  done
  SERVERS=()
}
trap stop_servers EXIT

# start_server <ログ> <コマンド...>。バックグラウンドで起動し、PID を SERVERS に積む
start_server() {
  local log=$1; shift
  setsid "$@" > "$log" 2>&1 < /dev/null &
  SERVERS+=($!)
}

# wait_http <URL> <秒>。応答が返る（HTTP のステータスは問わない）まで待つ
wait_http() {
  local url=$1 limit=$2 i
  for ((i = 0; i < limit; i++)); do
    curl -s -o /dev/null --max-time 2 "$url" && return 0
    sleep 1
  done
  return 1
}

measure_accessibility() {
  local tool="$WORK/a11y" front="$SRC/$FRONTEND_DIR" jar port=${A11Y_FRONTEND_PORT:-4173}
  [ -f "$front/package.json" ] || { fail "M-10: $FRONTEND_DIR/package.json がありません"; return; }
  group "アクセシビリティ検査（Playwright + axe-core）"
  if ! (
    set -e
    rm -rf "$tool"
    mkdir -p "$tool"
    cp "$COLLECTOR_DIR"/a11y/scan.mjs "$tool/"
    if [ -n "${QG_A11Y_TOOL_DIR:-}" ]; then
      # コンテナのイメージに Playwright・axe-core・Chromium が入っている
      ln -s "$QG_A11Y_TOOL_DIR/node_modules" "$tool/node_modules"
    else
      cp "$COLLECTOR_DIR"/a11y/{package.json,package-lock.json} "$tool/"
      cd "$tool"
      npm ci --no-audit --no-fund
      [ -n "${A11Y_CHROMIUM:-}" ] || npx playwright install chromium
    fi
  ); then
    fail "M-10: 検査ツールを用意できませんでした"; endgroup; return
  fi

  # バックエンド。measure_backend の verify で出来た実行可能 jar を使う
  if [ -n "${A11Y_BACKEND_PORT:-}" ]; then
    jar=$(find "$SRC/$BACKEND_DIR/target" -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' ! -name '*-sources.jar' \
      ! -name '*-javadoc.jar' 2>/dev/null | head -n 1 || true)
    if [ -z "$jar" ]; then
      fail "M-10: バックエンドの jar がありません（ビルドに失敗しています）"; endgroup; return
    fi
    start_server "$WORK/a11y-backend.log" java -jar "$jar" --server.port="$A11Y_BACKEND_PORT"
    if ! wait_http "http://127.0.0.1:${A11Y_BACKEND_PORT}/" "${A11Y_START_TIMEOUT:-120}"; then
      tail -n 50 "$WORK/a11y-backend.log" >&2
      stop_servers
      fail "M-10: バックエンドが起動しませんでした"; endgroup; return
    fi
  fi

  # フロントエンド。本番と同じビルド結果を vite preview で配る（/api の proxy は vite.config の server.proxy を引き継ぐ）
  if ! (cd "$front" && npx vite build); then
    stop_servers
    fail "M-10: フロントエンドのビルドに失敗しました"; endgroup; return
  fi
  start_server "$WORK/a11y-frontend.log" bash -c \
    "cd \"\$1\" && exec npx vite preview --host 127.0.0.1 --port \"\$2\" --strictPort" _ "$front" "$port"
  if ! wait_http "http://127.0.0.1:${port}/" "${A11Y_START_TIMEOUT:-120}"; then
    tail -n 50 "$WORK/a11y-frontend.log" >&2
    stop_servers
    fail "M-10: フロントエンドが起動しませんでした"; endgroup; return
  fi

  A11Y_BASE_URL="http://127.0.0.1:${port}" A11Y_PAGES="$A11Y_PAGES" \
    A11Y_READY_SELECTOR="${A11Y_READY_SELECTOR:-}" A11Y_OUTPUT="$REPORTS/frontend/axe-results.json" \
    node "$tool/scan.mjs" || fail "M-10: 検査できなかった画面があります（その画面は ERROR になります）"
  stop_servers
  endgroup
  rm -rf "$tool"
}

# --- Trivy / oasdiff。コンテナの中ではイメージに入れたバイナリ、外では版を固定した Docker イメージで動かす ----
oasdiff() {
  if [ "${QG_COLLECTOR_IN_CONTAINER:-}" = 1 ]; then
    command oasdiff "$@"
  else
    docker run --rm -v "$PWD:/w:ro" -w /w "$OASDIFF_IMAGE" "$@"
  fi
}

trivy() {
  if [ "${QG_COLLECTOR_IN_CONTAINER:-}" = 1 ]; then
    command trivy "$@"
  else
    # 出力は標準出力で受け取る（コンテナの root 権限で書かれたファイルを作業領域に残さない）
    docker run --rm -v "$PWD:/src:ro" -w /src -v quality-gate-collector-trivy:/root/.cache/ "$TRIVY_IMAGE" "$@"
  fi
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
    if (cd "$REPORTS" && oasdiff breaking openapi-base.yml openapi-head.yml --format json) > "$REPORTS/oasdiff.json"; then
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
  if ! (cd "$SRC" && trivy fs --quiet --format sarif --severity CRITICAL,HIGH,MEDIUM .) > "$REPORTS/trivy.sarif"; then
    rm -f "$REPORTS/trivy.sarif"
    fail "M-06: Trivy の実行に失敗しました"
  fi
  endgroup
}

if [ -n "${BACKEND_DIR:-}" ]; then
  measure_backend
  [ -z "${MUTATION_TARGET_CLASSES:-}" ] || measure_mutation
  measure_complexity
fi
[ -z "${FRONTEND_DIR:-}" ] || measure_frontend
[ -z "${A11Y_PAGES:-}" ] || measure_accessibility
[ -z "${OPENAPI_PATH:-}" ] || measure_breaking_changes
measure_vulnerabilities

log "計測結果:"
(cd "$REPORTS" && find . -type f ! -name '*.env' ! -name '*.tsv' | sort | sed 's/^/  /') >&2
if [ ${#FAILED[@]} -gt 0 ]; then
  warn "計測できなかったものがあります（${#FAILED[@]} 件）。該当の指標は ERROR になります"
  printf '  - %s\n' "${FAILED[@]}" >&2
fi
