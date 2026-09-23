package com.qualitygate.notify;

import com.qualitygate.domain.entity.Measurement;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.entity.Waiver;
import com.qualitygate.domain.metric.MetricCatalog;
import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.Verdict;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 通知の本文（FR-11-5: 合否・変化した指標・前回値 → 今回値・Run 詳細への直リンク）。
 *
 * <p>色の付かないメールでも合否が読めるよう、
 * ステータスは記号とラベルで書く（docs/initial/08-screen-design.md 3.1）。
 */
final class NotificationMessages {

    private static final int MAX_METRIC_LINES = 12;
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Tokyo"));

    private NotificationMessages() {
    }

    static NotificationMessage verdict(MonitoredRepository repository, Run run, Verdict prior,
                                       boolean reevaluated, List<Measurement> measurements,
                                       String detailUrl) {
        String shortSha = run.getCommitSha().substring(0, 7);
        String where = run.getPullRequestNumber() == null
                ? "%s · %s".formatted(run.getBranch(), shortSha)
                : "PR #%d · %s".formatted(run.getPullRequestNumber(), shortSha);
        String subject = "[quality-gate] %s: %s（%s）".formatted(repository.fullName(),
                verdictLabel(run.getVerdict()), where);

        List<String> lines = changedMetricLines(measurements);
        StringBuilder text = new StringBuilder(subject).append('\n');
        text.append("判定: ").append(prior == null ? "（初回）" : verdictLabel(prior))
                .append(" → ").append(verdictLabel(run.getVerdict())).append('\n');
        if (reevaluated) {
            text.append("コードの変更なしに、再評価で判定が変わりました"
                    + "（新しく公開された脆弱性・しきい値の変更・免除の期限切れなど）。\n");
        }
        if (!lines.isEmpty()) {
            text.append("\n注意が必要な指標:\n");
            lines.forEach(line -> text.append("- ").append(line).append('\n'));
        }
        text.append("\nRun 詳細: ").append(detailUrl).append('\n');

        return new NotificationMessage(subject, text.toString());
    }

    static NotificationMessage waiversExpiring(MonitoredRepository repository,
                                               List<Waiver> waivers, String waiversUrl,
                                               Instant now) {
        String subject = "[quality-gate] %s: 免除 %d 件の期限が 7 日以内に切れます"
                .formatted(repository.fullName(), waivers.size());
        StringBuilder text = new StringBuilder(subject).append('\n')
                .append("期限が切れると次の判定から再び数えられ、不合格に戻ることがあります。\n\n");
        waivers.stream().sorted(Comparator.comparing(Waiver::getExpiresAt)).forEach(w ->
                text.append("- ").append(DATE_TIME.format(w.getExpiresAt())).append(" まで: ")
                        .append(Objects.requireNonNullElse(w.getTitle(), w.getMetricId()))
                        .append("（").append(w.getReasonCategory().label()).append("）\n"));
        text.append("\n免除管理: ").append(waiversUrl).append('\n');
        return new NotificationMessage(subject, text.toString());
    }

    static NotificationMessage stale(MonitoredRepository repository, String what,
                                     Instant last, String detailUrl) {
        String subject = "[quality-gate] %s: %s".formatted(repository.fullName(), what);
        String text = subject + "\n最後の計測: " + (last == null ? "なし" : DATE_TIME.format(last))
                + "\nCI からの送信が止まっていないか確認してください。\n\nリポジトリ: " + detailUrl + "\n";
        return new NotificationMessage(subject, text);
    }

    /**
     * 不合格・注意・計測エラーの指標と、値が変わった指標。重いものから並べる。
     * 全指標を並べると、見るべき行が埋もれる。
     */
    private static List<String> changedMetricLines(List<Measurement> measurements) {
        List<Measurement> relevant = measurements.stream()
                .filter(m -> isProblem(m.getStatus()) || changed(m))
                .sorted(Comparator.comparingInt((Measurement m) -> rank(m.getStatus()))
                        .thenComparing(Measurement::getMetricId, MetricCatalog::compareByCatalogOrder))
                .toList();
        List<String> lines = new ArrayList<>();
        for (Measurement m : relevant) {
            if (lines.size() == MAX_METRIC_LINES) {
                lines.add("ほか %d 件".formatted(relevant.size() - MAX_METRIC_LINES));
                break;
            }
            String name = MetricCatalog.of(m.getMetricId()).name()
                    + (m.getComponentName() == null ? "" : "（" + m.getComponentName() + "）");
            String values = m.getValue() == null ? "値なし"
                    : m.getPreviousValue() == null ? format(m.getValue(), m.getUnit())
                    : format(m.getPreviousValue(), m.getUnit()) + " → " + format(m.getValue(), m.getUnit());
            lines.add("%s: %s %s".formatted(name, values, statusLabel(m.getStatus())));
        }
        return lines;
    }

    private static boolean isProblem(MeasurementStatus status) {
        return status == MeasurementStatus.FAIL || status == MeasurementStatus.ERROR
                || status == MeasurementStatus.WARN;
    }

    private static boolean changed(Measurement m) {
        return m.getValue() != null && m.getPreviousValue() != null
                && m.getValue().compareTo(m.getPreviousValue()) != 0;
    }

    private static int rank(MeasurementStatus status) {
        return switch (status) {
            case FAIL -> 0;
            case ERROR -> 1;
            case WARN -> 2;
            default -> 3;
        };
    }

    private static String format(BigDecimal value, String unit) {
        String number = value.stripTrailingZeros().toPlainString();
        return switch (unit == null ? "" : unit) {
            case "percent" -> number + "%";
            case "ms" -> number + "ms";
            case "rps" -> number + " req/s";
            case "count" -> number + " 件";
            default -> number;
        };
    }

    static String verdictLabel(Verdict verdict) {
        if (verdict == null) {
            return "◆ 判定できませんでした";
        }
        return switch (verdict) {
            case PASS -> "● 合格";
            case PASS_WITH_WARNINGS -> "▲ 注意つき合格";
            case FAIL -> "■ 不合格";
        };
    }

    private static String statusLabel(MeasurementStatus status) {
        return switch (status) {
            case PASS -> "（● 合格）";
            case WARN -> "（▲ 注意）";
            case FAIL -> "（■ 不合格）";
            case ERROR -> "（◆ 計測エラー）";
            case SKIP -> "（○ 未計測）";
            case REFERENCE -> "（◇ 参考値）";
            case NOT_APPLICABLE -> "（— 対象外）";
        };
    }
}
