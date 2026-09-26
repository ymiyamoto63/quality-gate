# shellcheck shell=bash
# M-02（PIT）: ミューテーションテスト。既定ブランチの計測でだけ全量を実行する。
# measure.sh が source する（単独では実行しない）。

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
