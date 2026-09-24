package com.qualitygate.github;

import com.qualitygate.domain.entity.Measurement;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.metric.MetricCatalog;
import com.qualitygate.domain.model.Verdict;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 判定結果を GitHub の Check Run として出す（要件定義書 3.3 の Phase 2。{@code enforcement: check-run}）。
 *
 * <p>{@code check-run} では合否に関わらず {@code neutral} で出す。PR 上で合否が見えるようにするだけで、
 * マージは止めない（D-4）。{@code blocking} では合格を {@code success}、不合格を {@code failure} にする
 * （必須チェックにするかどうかはリポジトリのブランチ保護の設定で決まる。Phase 3）。
 *
 * <p>Check Run は GitHub App でしか作れない（トークンでは作れない）。App に Checks の書き込み権限が要る。
 */
@Component
public class CheckRunPublisher {

    /** PR の Checks 欄に出る名前。再評価でも同じ名前で出し直すと、GitHub は最新のものを表示する。 */
    static final String NAME = "quality-gate";

    private static final Map<String, String> PERMISSIONS = Map.of("checks", "write");

    /** GitHub の output.summary の上限（65535 文字）より十分小さく切る。 */
    private static final int MAX_SUMMARY = 60_000;

    private final GitHubClient client;

    public CheckRunPublisher(GitHubClient client) {
        this.client = client;
    }

    public boolean available() {
        return client.usesApp();
    }

    /** Check Run を作り、その ID を返す。 */
    public long publish(MonitoredRepository repository, Run run, List<Measurement> measurements,
                        String enforcement, String detailUrl) {
        Map<String, Object> body = body(run, measurements, enforcement, detailUrl);
        return client.postRepositoryResource(repository.getOwner(), repository.getName(), "/check-runs", body,
                PERMISSIONS).path("id").asLong();
    }

    static Map<String, Object> body(Run run, List<Measurement> measurements, String enforcement, String detailUrl) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("title", titleOf(run.getVerdict()));
        output.put("summary", truncate(summaryOf(run, measurements, enforcement, detailUrl)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", NAME);
        body.put("head_sha", run.getCommitSha());
        body.put("status", "completed");
        body.put("conclusion", conclusionOf(run.getVerdict(), enforcement));
        body.put("completed_at", (run.getEvaluatedAt() != null ? run.getEvaluatedAt() : run.getMeasuredAt()).toString());
        body.put("details_url", detailUrl);
        body.put("external_id", run.getId().toString());
        body.put("output", output);
        return body;
    }

    static String conclusionOf(Verdict verdict, String enforcement) {
        if (!"blocking".equals(enforcement)) {
            return "neutral";
        }
        return verdict == Verdict.FAIL || verdict == null ? "failure" : "success";
    }

    static String titleOf(Verdict verdict) {
        if (verdict == null) {
            return "判定できませんでした";
        }
        return switch (verdict) {
            case PASS -> "合格";
            case PASS_WITH_WARNINGS -> "合格（警告あり）";
            case FAIL -> "不合格";
        };
    }

    private static String summaryOf(Run run, List<Measurement> measurements, String enforcement, String detailUrl) {
        StringBuilder summary = new StringBuilder();
        summary.append("**").append(titleOf(run.getVerdict())).append("**");
        if (!"blocking".equals(enforcement)) {
            summary.append("（`enforcement: ").append(enforcement).append("` のため、合否に関わらず neutral で報告しています）");
        }
        summary.append("\n\n| 指標 | コンポーネント | 判定 | 値 | 理由 |\n| --- | --- | --- | --- | --- |\n");
        measurements.stream()
                .sorted(Comparator.comparing(Measurement::getMetricId)
                        .thenComparing(m -> Objects.toString(m.getComponentName(), "")))
                .forEach(m -> summary.append("| ")
                        .append(cell(m.getMetricId() + " " + MetricCatalog.of(m.getMetricId()).name())).append(" | ")
                        .append(cell(Objects.toString(m.getComponentName(), "—"))).append(" | ")
                        .append(m.getStatus().name()).append(" | ")
                        .append(cell(valueOf(m.getValue(), m.getUnit()))).append(" | ")
                        .append(cell(Objects.toString(m.getReason(), ""))).append(" |\n"));
        summary.append("\n[quality-gate で詳細を見る](").append(detailUrl).append(")\n");
        return summary.toString();
    }

    private static String valueOf(BigDecimal value, String unit) {
        if (value == null) {
            return "—";
        }
        String plain = value.stripTrailingZeros().toPlainString();
        return "percent".equals(unit) ? plain + "%" : unit == null || "count".equals(unit) ? plain : plain + " " + unit;
    }

    /** Markdown の表のセル。区切り文字と改行を無害にする。 */
    private static String cell(String text) {
        return text.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    private static String truncate(String text) {
        return text.length() <= MAX_SUMMARY ? text : text.substring(0, MAX_SUMMARY) + "\n\n（長いため省略しました）";
    }
}
