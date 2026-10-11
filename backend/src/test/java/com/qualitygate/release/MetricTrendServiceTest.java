package com.qualitygate.release;

import com.qualitygate.domain.entity.Measurement;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.Verdict;
import com.qualitygate.domain.repo.MeasurementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 計測をまたいだ値の並べ方を確かめる。Run の選び方（同じコミットの重複除去）は ReleaseReportApiIT で確かめる。 */
class MetricTrendServiceTest {

    private final EvaluatedRuns evaluatedRuns = mock(EvaluatedRuns.class);
    private final MeasurementRepository measurements = mock(MeasurementRepository.class);
    private final MonitoredRepository repository = new MonitoredRepository(UUID.randomUUID(), "o", "r");
    private final MetricTrendService service = new MetricTrendService(evaluatedRuns, measurements, new ObjectMapper());

    /** 新しい順（EvaluatedRuns が返す順）。 */
    private final List<Run> newestFirst = new ArrayList<>();
    private final List<Measurement> values = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(evaluatedRuns.repository()).thenReturn(Optional.of(repository));
        when(evaluatedRuns.latestPerCommit(any(), anyInt())).thenReturn(newestFirst);
        when(measurements.findByRunIdIn(any())).thenReturn(values);
    }

    @Test
    void 計測は古い順に並べて点はその位置を指す() {
        Run newer = run("b", "2026-09-21T00:00:00Z", List.of("v1.1.0"));
        Run older = run("a", "2026-09-20T00:00:00Z", List.of());
        newestFirst.addAll(List.of(newer, older));
        values.add(measurement(newer, "M-01", "backend", null, MeasurementStatus.PASS, "82.5", "percent", ge("75")));
        values.add(measurement(older, "M-01", "backend", null, MeasurementStatus.FAIL, "70", "percent", ge("75")));

        MetricTrendResponse response = service.trends();

        assertThat(response.runs()).extracting(MetricTrendResponse.TrendRun::commitSha)
                .containsExactly("a".repeat(40), "b".repeat(40));
        // タグがあればタグで判定を開く
        assertThat(response.runs()).extracting(MetricTrendResponse.TrendRun::ref)
                .containsExactly("a".repeat(40), "v1.1.0");
        var points = response.metrics().getFirst().series().getFirst().points();
        assertThat(points).extracting(MetricTrendResponse.TrendPoint::runIndex).containsExactly(0, 1);
        assertThat(points).extracting(MetricTrendResponse.TrendPoint::status)
                .containsExactly(MeasurementStatus.FAIL, MeasurementStatus.PASS);
    }

    @Test
    void コンポーネントと計測条件ごとに線を分ける() {
        Run run = run("a", "2026-09-20T00:00:00Z", List.of());
        newestFirst.add(run);
        values.add(measurement(run, "M-01", "frontend", null, MeasurementStatus.PASS, "90", "percent", ge("75")));
        values.add(measurement(run, "M-01", "backend", null, MeasurementStatus.PASS, "80", "percent", ge("75")));
        values.add(measurement(run, "M-03", null, "staging", MeasurementStatus.PASS, "320", "ms", le("500")));
        values.add(measurement(run, "M-03", null, "load-test", MeasurementStatus.PASS, "410", "ms", le("500")));

        MetricTrendResponse response = service.trends();

        assertThat(response.metrics()).extracting(MetricTrendResponse.TrendMetric::metricId)
                .containsExactly("M-01", "M-03");
        assertThat(response.metrics().get(0).series()).extracting(MetricTrendResponse.TrendSeries::componentName)
                .containsExactly("backend", "frontend");
        assertThat(response.metrics().get(1).series()).extracting(MetricTrendResponse.TrendSeries::variantLabel)
                .containsExactly("load-test", "staging");
    }

    @Test
    void 計測エラーの点は値を持たず合否と合格ラインは判定時のものを使う() {
        Run newer = run("b", "2026-09-21T00:00:00Z", List.of());
        Run older = run("a", "2026-09-20T00:00:00Z", List.of());
        newestFirst.addAll(List.of(newer, older));
        // 途中で合格ラインを 75% から 80% に上げた
        values.add(measurement(older, "M-01", "backend", null, MeasurementStatus.PASS, "78", "percent", ge("75")));
        values.add(measurement(newer, "M-01", "backend", null, MeasurementStatus.ERROR, null, "percent", ge("80")));

        var points = service.trends().metrics().getFirst().series().getFirst().points();

        assertThat(points.get(0).thresholdValue()).isEqualByComparingTo("75");
        assertThat(points.get(0).threshold()).isEqualTo("≥ 75%");
        assertThat(points.get(1).status()).isEqualTo(MeasurementStatus.ERROR);
        assertThat(points.get(1).value()).isNull();
        assertThat(points.get(1).thresholdValue()).isEqualByComparingTo("80");
    }

    @Test
    void 縦軸はパーセントの指標だけ100に固定しエラー率は固定しない() {
        Run run = run("a", "2026-09-20T00:00:00Z", List.of());
        newestFirst.add(run);
        values.add(measurement(run, "M-01", "backend", null, MeasurementStatus.PASS, "80", "percent", ge("75")));
        values.add(measurement(run, "M-04", null, "staging", MeasurementStatus.PASS, "0.02", "percent", le("0.1")));
        values.add(measurement(run, "M-05", null, null, MeasurementStatus.PASS, "0", "count", le("0")));

        assertThat(service.trends().metrics()).extracting(MetricTrendResponse.TrendMetric::axisMax)
                .containsExactly(BigDecimal.valueOf(100), null, null);
    }

    @Test
    void 前回からの増加で判定する指標は合格ラインの線を持たない() {
        Run run = run("a", "2026-09-20T00:00:00Z", List.of());
        newestFirst.add(run);
        values.add(measurement(run, "M-10", "backend", null, MeasurementStatus.PASS, "3", "count",
                "{\"operator\":\"<=\",\"value\":0,\"basis\":\"increase\"}"));

        var point = service.trends().metrics().getFirst().series().getFirst().points().getFirst();

        assertThat(point.thresholdValue()).isNull();
        assertThat(point.threshold()).isEqualTo("前回から +0 件 以内");
    }

    @Test
    void 一度も計測していなければ空() {
        when(evaluatedRuns.repository()).thenReturn(Optional.empty());

        MetricTrendResponse response = service.trends();

        assertThat(response.runs()).isEmpty();
        assertThat(response.metrics()).isEmpty();
    }

    private Run run(String sha, String measuredAt, List<String> tags) {
        Run run = new Run(UUID.randomUUID(), repository.getId(), sha.repeat(40), "main", Instant.parse(measuredAt), 1);
        run.setTags(tags);
        run.markEvaluated(Verdict.PASS, Instant.parse(measuredAt));
        return run;
    }

    private Measurement measurement(Run run, String metricId, String component, String variant,
                                    MeasurementStatus status, String value, String unit, String threshold) {
        return new Measurement(UUID.randomUUID(), run.getId(), repository.getId(), metricId, component, variant,
                status, value == null ? null : new BigDecimal(value), unit, threshold, null, null, null,
                run.getMeasuredAt());
    }

    private static String ge(String value) {
        return "{\"operator\":\">=\",\"value\":%s}".formatted(value);
    }

    private static String le(String value) {
        return "{\"operator\":\"<=\",\"value\":%s}".formatted(value);
    }
}
