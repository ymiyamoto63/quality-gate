# shellcheck shell=bash
# M-01（Java。JaCoCo）/ M-08（契約テスト）/ M-11・M-12（テストの結果）: バックエンドのビルドとテスト。
# measure.sh が source する（単独では実行しない）。

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

  # M-11 / M-12 はすべてのテストの結果で判定する（契約テストに限らない）。
  # パターンは空白区切り。read は glob を展開しない
  local patterns pattern
  read -ra patterns <<< "${TEST_REPORTS:-surefire-reports/TEST-*.xml failsafe-reports/TEST-*.xml}"
  copied=0
  for pattern in "${patterns[@]}"; do
    for file in "$dir"/target/$pattern; do
      [ -e "$file" ] || continue
      cp "$file" "$REPORTS/tests/backend/"
      copied=$((copied + 1))
    done
  done
  [ "$copied" -gt 0 ] || fail "M-11/M-12: バックエンドのテストの結果がありません（target/${TEST_REPORTS:-surefire-reports/TEST-*.xml}）"
}
