package com.qualitygate.adapter;

import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.PerformanceSample;
import com.qualitygate.domain.report.RawMeasurement;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Gatling の simulation.log（テキスト形式）から M-03 / M-04 / M-05 を読む（docs/initial/02-metrics-spec.md M-03）。
 *
 * <p>k6 の summary と同じく、1 ファイルが 1 回の実行を表す。中央値を取るのは評価器の責務である。
 *
 * <p>simulation.log はタブ区切りの行指向で、{@code REQUEST} の行が 1 リクエストを表す。
 * <pre>
 * RUN      &lt;simulation&gt;  &lt;id&gt;  &lt;開始時刻&gt;  ...
 * REQUEST  &lt;グループ&gt;  &lt;リクエスト名&gt;  &lt;開始 ms&gt;  &lt;終了 ms&gt;  OK|KO  &lt;メッセージ&gt;
 * </pre>
 * Gatling の版で列が前後するため、{@code OK} / {@code KO} の列を探し、その直前 2 列を開始・終了時刻として読む。
 * Gatling 3.8 以降の既定はバイナリ形式で、これは読めない（テキスト形式で出力するか、k6 を使う）。
 *
 * <ul>
 *   <li>p95: 応答時間（終了 − 開始）の最近順位法による 95 パーセンタイル</li>
 *   <li>到達率: リクエスト数 ÷（最後の終了 − 最初の開始）</li>
 *   <li>シナリオ: Gatling のグループ名をシナリオ名として、グループごとの p95 を出す</li>
 * </ul>
 * metadata の {@code environment.warmupSeconds} があれば、最初のリクエストからその秒数の間に始まった
 * リクエストを集計から除く（k6 の {@code phase:measure} と同じ扱い）。
 */
@Component
public class GatlingLogAdapter implements ArtifactAdapter {

    private static final String REQUEST = "REQUEST";

    @Override
    public boolean supports(ArtifactType type) {
        return type == ArtifactType.GATLING_LOG;
    }

    @Override
    public NormalizedReport parse(InputStream in, ParseContext context) {
        String environment = K6SummaryAdapter.environmentOf(context);
        List<Request> requests = read(in);
        if (requests.isEmpty()) {
            throw new ArtifactFormatException(
                    "simulation.log に REQUEST の行がありません。負荷試験が実行されたか確認してください");
        }

        long warmupMs = warmupSecondsOf(context) * 1000L;
        long firstStart = requests.stream().mapToLong(Request::start).min().orElseThrow();
        List<Request> measured = requests.stream()
                .filter(r -> r.start() >= firstStart + warmupMs).toList();
        if (measured.isEmpty()) {
            throw new ArtifactFormatException(
                    "ウォームアップ（%d 秒）を除くとリクエストが残りません".formatted(warmupMs / 1000));
        }

        long start = measured.stream().mapToLong(Request::start).min().orElseThrow();
        long end = measured.stream().mapToLong(Request::end).max().orElseThrow();
        if (end <= start) {
            throw new ArtifactFormatException("リクエストの時刻から実行時間を求められません");
        }
        BigDecimal rate = BigDecimal.valueOf(measured.size() * 1000L)
                .divide(BigDecimal.valueOf(end - start), 4, RoundingMode.HALF_UP);
        long failed = measured.stream().filter(r -> !r.ok()).count();

        Map<String, List<Long>> byGroup = new TreeMap<>();
        for (Request request : measured) {
            if (!request.group().isBlank()) {
                byGroup.computeIfAbsent(request.group(), g -> new ArrayList<>()).add(request.elapsed());
            }
        }
        Map<String, BigDecimal> scenarios = new TreeMap<>();
        byGroup.forEach((group, elapsed) -> scenarios.put(group, p95(elapsed)));

        PerformanceSample sample = new PerformanceSample(
                p95(measured.stream().map(Request::elapsed).toList()), rate, measured.size(), failed,
                scenarios, environment, K6SummaryAdapter.environmentDetailOf(context));
        String component = context.componentName();
        return NormalizedReport.of(ArtifactType.GATLING_LOG, List.of(
                RawMeasurement.of(K6SummaryAdapter.M_P95, component, sample.p95Ms(), "ms",
                        sample.toDetail()).withVariant(environment),
                RawMeasurement.of(K6SummaryAdapter.M_THROUGHPUT, component, sample.successRate(), "rps",
                        sample.toDetail()).withVariant(environment),
                RawMeasurement.of(K6SummaryAdapter.M_ERROR_RATE, component, sample.errorRatePercent(),
                        "percent", sample.toDetail()).withVariant(environment)),
                List.of());
    }

    /** 最近順位法（昇順に並べて ceil(0.95 × n) 番目）。 */
    static BigDecimal p95(List<Long> elapsed) {
        List<Long> sorted = elapsed.stream().sorted().toList();
        int rank = (int) Math.ceil(0.95 * sorted.size());
        return BigDecimal.valueOf(sorted.get(Math.max(rank, 1) - 1));
    }

    private static long warmupSecondsOf(ParseContext context) {
        Object environment = context.metadata().get(K6SummaryAdapter.ENVIRONMENT);
        if (environment instanceof Map<?, ?> map && map.get("warmupSeconds") instanceof Number seconds) {
            return Math.max(0, seconds.longValue());
        }
        return 0;
    }

    private static List<Request> read(InputStream raw) {
        List<Request> requests = new ArrayList<>();
        BufferedInputStream in = new BufferedInputStream(raw);
        try {
            requireText(in);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            int number = 0;
            boolean sawRecord = false;
            while ((line = reader.readLine()) != null) {
                number++;
                String[] fields = line.split("\t", -1);
                switch (fields[0]) {
                    case "RUN", "USER", "GROUP", "ERROR", "ASSERTION" -> sawRecord = true;
                    case REQUEST -> {
                        sawRecord = true;
                        requests.add(requestOf(fields, number));
                    }
                    default -> { /* 空行や未知の記録は読み飛ばす */ }
                }
            }
            if (!sawRecord) {
                throw new ArtifactFormatException(
                        "Gatling の simulation.log ではありません（RUN / REQUEST などの記録がありません）");
            }
        } catch (IOException e) {
            throw new ArtifactFormatException("simulation.log の読み取りに失敗しました: " + e.getMessage(), e);
        }
        return requests;
    }

    /** バイナリ形式（Gatling 3.8 以降の既定）を、分かる理由で拒否する。 */
    private static void requireText(BufferedInputStream in) throws IOException {
        in.mark(512);
        byte[] head = in.readNBytes(512);
        in.reset();
        for (byte b : head) {
            if (b == 0) {
                throw new ArtifactFormatException(
                        "simulation.log がバイナリ形式です（Gatling 3.8 以降の既定）。テキスト形式の simulation.log を送るか、"
                                + " k6 の summary（k6-summary）で送信してください");
            }
        }
    }

    private static Request requestOf(String[] fields, int number) {
        for (int i = 3; i < fields.length; i++) {
            if ("OK".equals(fields[i]) || "KO".equals(fields[i])) {
                try {
                    long start = Long.parseLong(fields[i - 2].strip());
                    long end = Long.parseLong(fields[i - 1].strip());
                    String group = i >= 4 ? fields[i - 4].strip() : "";
                    return new Request(group, start, end, "OK".equals(fields[i]));
                } catch (NumberFormatException e) {
                    throw new ArtifactFormatException(
                            "%d 行目の REQUEST の時刻が数値ではありません".formatted(number), e);
                }
            }
        }
        throw new ArtifactFormatException("%d 行目の REQUEST に OK / KO の列がありません".formatted(number));
    }

    private record Request(String group, long start, long end, boolean ok) {
        long elapsed() {
            return Math.max(0, end - start);
        }
    }
}
