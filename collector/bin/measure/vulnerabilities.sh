# shellcheck shell=bash
# M-06 / M-12（Trivy の脆弱性とシークレットの走査）: 依存関係を取得した後の作業ツリーを走査する（対象の CI と同じ順序）。
# measure.sh が source する（単独では実行しない）。

measure_vulnerabilities() {
  group "脆弱性とシークレットのスキャン（${TRIVY_IMAGE}）"
  # 走査する対象を明示し、submit.sh がメタデータ（scanners）で申告する。
  # quality-gate は脆弱性を M-06、シークレットを M-12 に振り分ける
  if ! (cd "$SRC" && trivy fs --quiet --scanners vuln,secret --format sarif --severity CRITICAL,HIGH,MEDIUM .) \
      > "$REPORTS/trivy.sarif"; then
    rm -f "$REPORTS/trivy.sarif"
    fail "M-06/M-12: Trivy の実行に失敗しました"
  fi
  endgroup
}
