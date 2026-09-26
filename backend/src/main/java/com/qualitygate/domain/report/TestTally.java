package com.qualitygate.domain.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * テストの結果別件数と、M-11 の計算式（docs/spec/02-metrics-spec.md M-11）。
 *
 * <p>式をここに 1 つだけ置く。アダプタ（1 ファイル分の参考値）と評価器
 * （複数ファイルを合算した判定値）が同じ式を使わないと、画面の値と判定が食い違う。
 * 合算は件数で行う。割合どうしを平均すると、件数の違うレポートが同じ重みで混ざる。
 *
 * @param flaky 再実行で成功したテスト。成功に数えるが、不安定さとして残す
 */
public record TestTally(long passed, long failed, long errored, long skipped, long flaky) {

    public static final TestTally EMPTY = new TestTally(0, 0, 0, 0, 0);

    /** テスト 1 件の結果を数える。未知の結果は失敗に倒す（fail-closed）。 */
    public TestTally plus(String outcome) {
        return switch (outcome == null ? "" : outcome) {
            case "passed" -> plus(new TestTally(1, 0, 0, 0, 0));
            case "skipped" -> plus(new TestTally(0, 0, 0, 1, 0));
            case "flaky" -> plus(new TestTally(0, 0, 0, 0, 1));
            case "errored" -> plus(new TestTally(0, 0, 1, 0, 0));
            default -> plus(new TestTally(0, 1, 0, 0, 0));
        };
    }

    public TestTally plus(TestTally other) {
        return new TestTally(passed + other.passed, failed + other.failed,
                errored + other.errored, skipped + other.skipped, flaky + other.flaky);
    }

    /** 実行されたテスト数。スキップは実行していないため含めない。 */
    public long executed() {
        return succeeded() + failed + errored;
    }

    /** 成功したテスト数（再実行で成功したものを含む）。 */
    public long succeeded() {
        return passed + flaky;
    }

    /**
     * {@code 成功 / 実行 × 100}。実行 0 件なら値を持たせない（100% ではない）。
     *
     * <p>切り捨てで丸める。1,000 万件中 1 件の失敗を四捨五入で 100% と表示すると、
     * 「100% なのに不合格」という読めない画面になる。
     */
    public BigDecimal successRate() {
        if (executed() == 0) {
            return null;
        }
        return BigDecimal.valueOf(succeeded() * 100L)
                .divide(BigDecimal.valueOf(executed()), 4, RoundingMode.DOWN);
    }

    /**
     * 成功率が {@code minimum}（%）以上か。丸めた値ではなく件数で比べる。
     * 実行 0 件は満たさない。
     */
    public boolean meets(BigDecimal minimum) {
        if (executed() == 0) {
            return false;
        }
        BigDecimal left = BigDecimal.valueOf(succeeded() * 100L);
        BigDecimal right = minimum.multiply(BigDecimal.valueOf(executed()));
        return left.compareTo(right) >= 0;
    }

    /** 測定値の内訳（{@code detail}）として保存する形。キーの並びは画面の表示順。 */
    public Map<String, Object> toDetail() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("executed", executed());
        detail.put("passed", passed);
        detail.put("failed", failed);
        detail.put("errored", errored);
        detail.put("skipped", skipped);
        detail.put("flaky", flaky);
        return detail;
    }

    public static TestTally fromDetail(Map<String, Object> detail) {
        return new TestTally(count(detail, "passed"), count(detail, "failed"),
                count(detail, "errored"), count(detail, "skipped"), count(detail, "flaky"));
    }

    private static long count(Map<String, Object> detail, String key) {
        return detail.get(key) instanceof Number number ? number.longValue() : 0;
    }
}
