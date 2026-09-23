package com.qualitygate.adapter;

import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.PerformanceSample;
import com.qualitygate.domain.report.RawMeasurement;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * k6 の summary JSON から M-03 / M-04 / M-05 を読む（docs/initial/02-metrics-spec.md M-03）。
 *
 * <p>1 ファイルは 1 回の実行を表す。仕様は 3 回実行して中央値を採るため、
 * CI は同じ Run に 3 ファイルを送る。中央値を取るのは評価器の責務である。
 *
 * <p>受け付ける形は 2 通り。どちらも {@code metrics} の下に指標が並ぶ。
 * <ul>
 *   <li>{@code k6 run --summary-export}: {@code "http_req_duration": {"p(95)": 412.5, ...}}</li>
 *   <li>{@code handleSummary} で {@code JSON.stringify(data)} したもの:
 *       {@code "http_req_duration": {"type": "trend", "values": {"p(95)": 412.5}}}</li>
 * </ul>
 *
 * <p>シナリオ単位の p95 は {@code http_req_duration{scenario:dashboard}} のような
 * タグ付きの部分指標から読む。k6 はしきい値を定義したタグ付き指標だけを出力するため、
 * シナリオごとに {@code thresholds} を書いておく必要がある。
 *
 * <p>{@code {phase:measure}} のタグ付き部分指標があれば、ウォームアップを除いた値として優先する。
 *
 * <p>{@code http_req_failed} は Rate 型で、{@code passes} が「真（= 失敗した）」の件数である。
 * 名前と意味が逆に見えるため取り違えやすい。
 */
@Component
public class K6SummaryAdapter implements ArtifactAdapter {

    static final String M_P95 = "M-03";
    static final String M_THROUGHPUT = "M-04";
    static final String M_ERROR_RATE = "M-05";

    /** 計測環境を表すメタデータのキー。 */
    public static final String ENVIRONMENT = "environment";

    private static final Pattern SCENARIO_METRIC =
            Pattern.compile("^http_req_duration\\{\\s*scenario\\s*:\\s*([^}]+?)\\s*}$");

    private final ObjectMapper objectMapper;

    public K6SummaryAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ArtifactType type) {
        return type == ArtifactType.K6_SUMMARY;
    }

    @Override
    public NormalizedReport parse(InputStream in, ParseContext context) {
        String environment = environmentOf(context);
        if (context.metadataFlag("aborted")) {
            // 部分的な結果で判定しない（docs/initial/02-metrics-spec.md M-03 境界条件）
            throw new ArtifactFormatException(
                    "k6 の実行が異常終了しています（metadata の aborted=true）。部分的な結果では判定しません");
        }

        JsonNode metrics = read(in).path("metrics");
        if (!metrics.isObject()) {
            throw new ArtifactFormatException(
                    "k6 の summary ではありません（metrics がありません）。"
                            + " k6 run --summary-export=<file> の出力を送信してください");
        }

        BigDecimal p95 = number(valuesOf(measured(metrics, "http_req_duration")), "p(95)")
                .orElseThrow(() -> new ArtifactFormatException(
                        "http_req_duration の p(95) がありません。summaryTrendStats に p(95) を含めてください"));

        JsonNode reqs = valuesOf(measured(metrics, "http_reqs"));
        long requests = number(reqs, "count").map(BigDecimal::longValue).orElse(0L);
        if (requests == 0) {
            throw new ArtifactFormatException(
                    "HTTP リクエストが 1 件も記録されていません。負荷試験が実行されたか確認してください");
        }
        BigDecimal requestRate = number(reqs, "rate").orElseThrow(() ->
                new ArtifactFormatException("http_reqs の rate がありません"));

        long failed = failedRequestsOf(valuesOf(measured(metrics, "http_req_failed")), requests);

        Map<String, BigDecimal> scenarios = new TreeMap<>();
        for (Map.Entry<String, JsonNode> entry : metrics.properties()) {
            Matcher matcher = SCENARIO_METRIC.matcher(entry.getKey());
            if (matcher.matches()) {
                number(valuesOf(entry.getValue()), "p(95)")
                        .ifPresent(value -> scenarios.put(matcher.group(1), value));
            }
        }

        PerformanceSample sample = new PerformanceSample(p95, requestRate, requests, failed,
                scenarios, environment, environmentDetailOf(context));
        String component = context.componentName();
        List<RawMeasurement> measurements = List.of(
                RawMeasurement.of(M_P95, component, sample.p95Ms(), "ms", sample.toDetail())
                        .withVariant(environment),
                RawMeasurement.of(M_THROUGHPUT, component, sample.successRate(), "rps",
                        sample.toDetail()).withVariant(environment),
                RawMeasurement.of(M_ERROR_RATE, component, sample.errorRatePercent(), "percent",
                        sample.toDetail()).withVariant(environment));
        return NormalizedReport.of(ArtifactType.K6_SUMMARY, measurements, List.of());
    }

    /**
     * 失敗件数。{@code passes} / {@code fails} があれば件数で、無ければ率から求める。
     * どちらも無ければ 0 件とはみなさない（失敗の有無が分からない）。
     */
    private static long failedRequestsOf(JsonNode failedMetric, long requests) {
        var passes = number(failedMetric, "passes");
        if (passes.isPresent()) {
            return passes.get().longValue();
        }
        var rate = number(failedMetric, "rate").or(() -> number(failedMetric, "value"));
        if (rate.isPresent()) {
            return rate.get().multiply(BigDecimal.valueOf(requests))
                    .setScale(0, java.math.RoundingMode.HALF_UP).longValue();
        }
        throw new ArtifactFormatException(
                "http_req_failed がありません。k6 v0.31 以降で実行しているか確認してください");
    }

    /**
     * 計測環境の名前。系列を分ける軸（{@code variant}）になる。
     *
     * <p>取り込み時に存在は検証済みだが、名前の無い環境は系列に置けないため再度確かめる。
     */
    static String environmentOf(ParseContext context) {
        Object environment = context.metadata().get(ENVIRONMENT);
        String name = null;
        if (environment instanceof Map<?, ?> map && map.get("name") instanceof String text) {
            name = text;
        } else if (environment instanceof String text) {
            name = text;
        }
        if (name == null || name.isBlank()) {
            throw new ArtifactFormatException(
                    "性能計測の成果物には metadata の environment.name（計測環境の名前）が必要です");
        }
        return name.strip();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> environmentDetailOf(ParseContext context) {
        Object environment = context.metadata().get(ENVIRONMENT);
        return environment instanceof Map<?, ?> map
                ? Map.copyOf((Map<String, Object>) map)
                : Map.of("name", String.valueOf(environment));
    }

    /**
     * 計測区間の指標。{@code {phase:measure}} のタグ付き部分指標があればそちらを使う。
     *
     * <p>ウォームアップ（60 秒）は集計から除く（docs/initial/02-metrics-spec.md M-03 計測条件）。
     * k6 の全体の指標にはウォームアップのリクエストも含まれるため、計測区間のリクエストに
     * {@code phase: measure} のタグを付けてしきい値を定義し、その部分指標を出力させる
     * （perf/k6/quality-gate.js）。タグが無ければ全体の指標を使う。
     */
    private static JsonNode measured(JsonNode metrics, String name) {
        JsonNode tagged = metrics.path(name + "{phase:measure}");
        return tagged.isObject() ? tagged : metrics.path(name);
    }

    /** handleSummary の形（values の下）と summary-export の形（直下）を吸収する。 */
    private static JsonNode valuesOf(JsonNode metric) {
        return metric.has("values") ? metric.path("values") : metric;
    }

    private static java.util.Optional<BigDecimal> number(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isNumber()
                ? java.util.Optional.of(value.decimalValue())
                : java.util.Optional.empty();
    }

    private JsonNode read(InputStream in) {
        try {
            JsonNode root = objectMapper.readTree(in);
            if (root == null || !root.isObject()) {
                throw new ArtifactFormatException("k6 の summary が空、または JSON オブジェクトではありません");
            }
            return root;
        } catch (JacksonException e) {
            throw new ArtifactFormatException(
                    "k6 の summary を JSON として解析できませんでした: " + e.getMessage(), e);
        }
    }
}
