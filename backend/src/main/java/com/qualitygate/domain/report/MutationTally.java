package com.qualitygate.domain.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ミューテーションの status 別件数と、M-02 の計算式（docs/spec/02-metrics-spec.md M-02）。
 *
 * <p>式をここに 1 つだけ置く。アダプタ（1 ファイル分の参考値）と評価器
 * （複数ファイルを合算した判定値）が同じ式を使わないと、画面の値と判定が食い違う。
 *
 * <p>合算は件数で行う。割合どうしを平均すると、母数の違うモジュールが
 * 同じ重みで混ざってしまう。
 *
 * @param notCompleted STARTED / NOT_STARTED / 未知の status。解析が完了しなかった mutation
 */
public record MutationTally(
        long killed,
        long timedOut,
        long survived,
        long noCoverage,
        long nonViable,
        long runError,
        long memoryError,
        long notCompleted) {

    public static final MutationTally EMPTY = new MutationTally(0, 0, 0, 0, 0, 0, 0, 0);

    /** PIT の status 1 件を数える。対応は docs/spec/02-metrics-spec.md M-02 の表に従う。 */
    public MutationTally plusStatus(String status) {
        return switch (status == null ? "" : status) {
            case "KILLED" -> plus(new MutationTally(1, 0, 0, 0, 0, 0, 0, 0));
            case "TIMED_OUT" -> plus(new MutationTally(0, 1, 0, 0, 0, 0, 0, 0));
            case "SURVIVED" -> plus(new MutationTally(0, 0, 1, 0, 0, 0, 0, 0));
            case "NO_COVERAGE" -> plus(new MutationTally(0, 0, 0, 1, 0, 0, 0, 0));
            case "NON_VIABLE" -> plus(new MutationTally(0, 0, 0, 0, 1, 0, 0, 0));
            case "RUN_ERROR" -> plus(new MutationTally(0, 0, 0, 0, 0, 1, 0, 0));
            case "MEMORY_ERROR" -> plus(new MutationTally(0, 0, 0, 0, 0, 0, 1, 0));
            // 解析が打ち切られた mutation と未知の status。黙って消えないよう件数に残す
            default -> plus(new MutationTally(0, 0, 0, 0, 0, 0, 0, 1));
        };
    }

    public MutationTally plus(MutationTally other) {
        return new MutationTally(killed + other.killed, timedOut + other.timedOut,
                survived + other.survived, noCoverage + other.noCoverage,
                nonViable + other.nonViable, runError + other.runError,
                memoryError + other.memoryError, notCompleted + other.notCompleted);
    }

    /** 生成されたミューテーションの総数。 */
    public long total() {
        return scored() + failed();
    }

    /** 式の分母。NO_COVERAGE を含めるのは、除くと未テスト箇所が多いほど高く出るため。 */
    public long scored() {
        return killed + timedOut + survived + noCoverage;
    }

    /** 分母から除く、実行・生成に失敗したもの。多すぎれば計測自体が疑わしい。 */
    public long failed() {
        return nonViable + runError + memoryError + notCompleted;
    }

    /**
     * {@code (Killed + Timeout) / (Killed + Timeout + Survived + NoCoverage) × 100}。
     * 分母が 0 なら値を持たせない（0% でも 100% でもない）。
     */
    public BigDecimal score() {
        if (scored() == 0) {
            return null;
        }
        return BigDecimal.valueOf((killed + timedOut) * 100L)
                .divide(BigDecimal.valueOf(scored()), 4, RoundingMode.HALF_UP);
    }

    /** 全体に占める割合（%）。総数 0 なら 0。 */
    public BigDecimal ratioOf(long part) {
        if (total() == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(part * 100L)
                .divide(BigDecimal.valueOf(total()), 2, RoundingMode.HALF_UP);
    }

    /** 測定値の内訳（{@code detail}）として保存する形。キーの並びは画面の表示順。 */
    public Map<String, Object> toDetail() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("killed", killed);
        detail.put("timedOut", timedOut);
        detail.put("survived", survived);
        detail.put("noCoverage", noCoverage);
        detail.put("nonViable", nonViable);
        detail.put("runError", runError);
        detail.put("memoryError", memoryError);
        detail.put("notCompleted", notCompleted);
        detail.put("totalMutations", total());
        return detail;
    }

    public static MutationTally fromDetail(Map<String, Object> detail) {
        return new MutationTally(count(detail, "killed"), count(detail, "timedOut"),
                count(detail, "survived"), count(detail, "noCoverage"),
                count(detail, "nonViable"), count(detail, "runError"),
                count(detail, "memoryError"), count(detail, "notCompleted"));
    }

    private static long count(Map<String, Object> detail, String key) {
        return detail.get(key) instanceof Number number ? number.longValue() : 0;
    }
}
