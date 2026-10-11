package com.qualitygate.release;

import com.qualitygate.domain.entity.Measurement;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.metric.MetricCatalog;
import com.qualitygate.domain.metric.MetricGuide;
import com.qualitygate.domain.metric.Threshold;
import com.qualitygate.domain.repo.MeasurementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 指標の推移。合格ライン内でも値が悪くなっていないかを、計測をまたいで見せる。
 *
 * <p>判定はし直さない。点の合否と合格ラインは、それぞれの計測の判定時に焼き付けた値をそのまま使う
 * （合格ラインを途中で変えても、過去の点の合否は当時の合格ラインで示す）。
 */
@Service
public class MetricTrendService {

    /** 横軸に並べる計測の数。判定の履歴と同じ。 */
    static final int RUN_LIMIT = ReleaseReportService.HISTORY_LIMIT;

    /** 縦軸を 0〜100 に固定しない % の指標。エラー率の合格ラインは 0.1% で、0〜100 では線が底に張りつく。 */
    private static final String ERROR_RATE = MetricCatalog.M_ERROR_RATE;
    private static final BigDecimal PERCENT_MAX = BigDecimal.valueOf(100);

    private final EvaluatedRuns evaluatedRuns;
    private final MeasurementRepository measurements;
    private final ObjectMapper objectMapper;

    MetricTrendService(EvaluatedRuns evaluatedRuns, MeasurementRepository measurements, ObjectMapper objectMapper) {
        this.evaluatedRuns = evaluatedRuns;
        this.measurements = measurements;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public MetricTrendResponse trends() {
        Optional<MonitoredRepository> repository = evaluatedRuns.repository();
        if (repository.isEmpty()) {
            return new MetricTrendResponse(List.of(), List.of());
        }
        List<Run> runs = new ArrayList<>(evaluatedRuns.latestPerCommit(repository.get(), RUN_LIMIT));
        // グラフは左から右へ時間が進むため、古い順に並べ替える
        Collections.reverse(runs);
        Map<UUID, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < runs.size(); i++) {
            indexOf.put(runs.get(i).getId(), i);
        }

        List<Measurement> all = runs.isEmpty() ? List.of() : measurements.findByRunIdIn(indexOf.keySet());
        Map<String, List<Measurement>> byMetric = all.stream()
                .collect(Collectors.groupingBy(Measurement::getMetricId));
        List<MetricTrendResponse.TrendMetric> metrics = byMetric.keySet().stream()
                .sorted(MetricCatalog::compareByCatalogOrder)
                .map(metricId -> metricOf(metricId, byMetric.get(metricId), indexOf))
                .toList();

        return new MetricTrendResponse(runs.stream().map(run -> new MetricTrendResponse.TrendRun(
                run.getMeasuredAt(), run.getCommitSha(), run.getTags(), run.getVerdict(),
                ReleaseReportService.refOf(run))).toList(), metrics);
    }

    private MetricTrendResponse.TrendMetric metricOf(String metricId, List<Measurement> values,
                                                     Map<UUID, Integer> indexOf) {
        Comparator<Measurement> byRun = Comparator.comparing(m -> indexOf.get(m.getRunId()));
        // 単位は最新の計測のものを使う（値の無い計測エラーにも単位は付く）
        String unit = values.stream().max(byRun).map(Measurement::getUnit).orElse(null);

        Map<List<String>, List<Measurement>> bySeries = new LinkedHashMap<>();
        values.stream()
                .sorted(Comparator.comparing((Measurement m) -> Objects.toString(m.getComponentName(), ""))
                        .thenComparing(m -> Objects.toString(m.getVariant(), ""))
                        .thenComparing(byRun))
                .forEach(m -> bySeries.computeIfAbsent(
                        Arrays.asList(m.getComponentName(), m.getVariant()), key -> new ArrayList<>()).add(m));
        List<MetricTrendResponse.TrendSeries> series = bySeries.entrySet().stream()
                .map(entry -> new MetricTrendResponse.TrendSeries(entry.getKey().get(0), entry.getKey().get(1),
                        entry.getValue().stream().map(m -> pointOf(m, indexOf.get(m.getRunId()))).toList()))
                .toList();

        return new MetricTrendResponse.TrendMetric(metricId, MetricCatalog.of(metricId).name(),
                MetricGuide.of(metricId).summary(), unit,
                "percent".equals(unit) && !ERROR_RATE.equals(metricId) ? PERCENT_MAX : null, series);
    }

    private MetricTrendResponse.TrendPoint pointOf(Measurement m, int runIndex) {
        Threshold threshold = Threshold.parse(objectMapper, m.getThreshold());
        return new MetricTrendResponse.TrendPoint(runIndex, m.getStatus(), m.getValue(),
                ThresholdText.lineOf(threshold),
                ThresholdText.of(threshold, m.getUnit()),
                threshold.isRelaxed(),
                ThresholdText.defaultOf(threshold, m.getUnit()));
    }
}
