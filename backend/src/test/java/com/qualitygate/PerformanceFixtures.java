package com.qualitygate;

/** 結合テストで送る k6 の summary（専有ランナーで 3 回実行し、すべて合格する値）。 */
final class PerformanceFixtures {

    /** 計測環境のメタデータ。性能の成果物には必須。 */
    static final String METADATA =
            "{\"environment\":{\"name\":\"perf-staging\",\"runner\":\"self-hosted\","
                    + "\"cpu\":4,\"memory\":\"8GiB\",\"datasetProfile\":\"prod-like\"}}";

    private PerformanceFixtures() {
    }

    /** {@code n} 回目の実行の summary。p95 は 300ms 前後、エラー率 0%。 */
    static String summary(int n) {
        return """
                {"metrics": {
                  "http_req_duration": {"avg": 180.2, "p(95)": %d},
                  "http_reqs": {"count": 15000, "rate": 50.0},
                  "http_req_failed": {"passes": 0, "fails": 15000, "value": 0}
                }}
                """.formatted(300 + n);
    }
}
