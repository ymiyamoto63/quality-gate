# shellcheck shell=bash
# M-14（Trivy のライセンスの走査）: 依存関係を取得した後の作業ツリーを走査する。
# measure.sh が source する（単独では実行しない）。

# 深刻度で絞らない。1 つのパッケージに並ぶ緩いライセンス（MIT など）まで見ないと、
# デュアルライセンスのパッケージを厳しいほうのライセンスで数えてしまう（選べるものは緩いほうを採る）
measure_licenses() {
  group "ライセンスの走査（${TRIVY_IMAGE}）"
  if ! (cd "$SRC" && trivy fs --quiet --scanners license --format sarif .) > "$REPORTS/trivy-license.sarif"; then
    rm -f "$REPORTS/trivy-license.sarif"
    fail "M-14: Trivy（ライセンス）の実行に失敗しました"
  fi
  endgroup
}
