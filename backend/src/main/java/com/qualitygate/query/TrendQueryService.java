package com.qualitygate.query;

import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.metric.MetricCatalog;
import com.qualitygate.domain.metric.MetricDefinition;
import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.RunnerType;
import com.qualitygate.domain.repo.MeasurementRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.TrendRow;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.error.ErrorCode;
import com.qualitygate.query.dto.TrendResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 指標の時系列（S-05）の読み取りモデル。
 *
 * <p>系列の分割はサーバが行う（FR-08-2）。「どの計測が同じ条件で比較できるか」は
 * 指標の性質に属する判断であり、画面が決めるものではない。
 */
@Service
public class TrendQueryService {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    /** 既定の期間（FR-08-1）。 */
    static final Duration DEFAULT_RANGE = Duration.ofDays(30);
    /** 期間の上限（FR-08-1）。点が増えすぎて応答が重くなるのを防ぐ。 */
    static final Duration MAX_RANGE = Duration.ofDays(730);

    /**
     * 色を割り当てられる系列の数。
     *
     * <p>検証済みの配色は 3 色（docs/08-screen-design.md 3.5）。色を増やして
     * 系列を増やすことはしない。生成した 4 色目は色覚特性下で既存の色と
     * 見分けがつかなくなり、検証の意味が失われる。
     */
    static final int MAX_COLORED_SERIES = 3;

    private final MonitoredRepositoryRepository repositories;
    private final MeasurementRepository measurements;
    private final ObjectMapper objectMapper;

    public TrendQueryService(MonitoredRepositoryRepository repositories,
                             MeasurementRepository measurements, ObjectMapper objectMapper) {
        this.repositories = repositories;
        this.measurements = measurements;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public TrendResponse trend(java.util.UUID repositoryId, String metricId, String branch,
                               Instant from, Instant to) {
        MonitoredRepository repository = repositories.findById(repositoryId)
                .orElseThrow(() -> ApiException.notFound("リポジトリ", repositoryId));
        MetricDefinition definition = MetricCatalog.of(metricId);

        String targetBranch = branch == null || branch.isBlank()
                ? repository.getDefaultBranch() : branch;
        Range range = Range.of(from, to);

        List<TrendRow> rows = measurements.findTrend(repositoryId, metricId, targetBranch,
                range.from(), range.to());

        return new TrendResponse(metricId, definition.name(), unitOf(rows),
                thresholdOf(rows), thresholdChanged(rows), targetBranch,
                range.from(), range.to(), seriesOf(rows, definition));
    }

    /**
     * 系列に分ける。
     *
     * <p>分割の軸は「同じ条件で比較できるか」である。コンポーネントは常に別物
     * （backend と frontend のカバレッジを 1 本の線にしても意味がない）。
     * 計測環境は、値が環境に左右される指標でのみ軸になる。性能以外の指標まで
     * ランナー種別で割ると、同じ値の系列が無意味に 2 本に割れる。
     */
    private List<TrendResponse.TrendSeries> seriesOf(List<TrendRow> rows,
                                                MetricDefinition definition) {
        Map<Key, List<TrendRow>> grouped = new LinkedHashMap<>();
        List<TrendRow> componentLess = new ArrayList<>();

        for (TrendRow row : rows) {
            RunnerType environment = definition.environmentSensitive() ? row.runnerType() : null;
            if (row.componentName() == null) {
                componentLess.add(row);
                continue;
            }
            grouped.computeIfAbsent(new Key(row.componentName(), environment),
                    k -> new ArrayList<>()).add(row);
        }

        mergeComponentLess(grouped, componentLess, definition);

        // 色は系列の同一性に従って固定する。並び順で振ると、絞り込みで
        // 系列が減ったときに生き残った系列の色が変わる。
        List<Key> keys = grouped.keySet().stream().sorted(Key::compareTo).toList();

        List<TrendResponse.TrendSeries> series = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            Key key = keys.get(i);
            List<TrendResponse.TrendPoint> points = grouped.get(key).stream()
                    .sorted(java.util.Comparator.comparing(TrendRow::measuredAt))
                    .map(TrendQueryService::pointOf)
                    .toList();
            series.add(new TrendResponse.TrendSeries(key.id(), key.label(), key.componentName(),
                    judged(points), i % MAX_COLORED_SERIES, points));
        }
        return series;
    }

    /**
     * コンポーネントを持たない点を、各コンポーネントの系列に配る。
     *
     * <p>スキップと計測エラーはコンポーネント単位では起こらない。指標ごと Run ごとに
     * 起きるため、判定結果にコンポーネント名が付かない。これをそのまま別系列にすると、
     * <strong>欠測が「もう 1 本の線」として現れる</strong>。未計測は線を途切れさせる
     * ものであって、新しい系列ではない（FR-08-6）。
     *
     * <p>コンポーネント別の系列が 1 つも無い指標（脆弱性件数など）では、
     * これらがそのまま単一の系列になる。
     */
    private static void mergeComponentLess(Map<Key, List<TrendRow>> grouped,
                                           List<TrendRow> componentLess,
                                           MetricDefinition definition) {
        if (componentLess.isEmpty()) {
            return;
        }
        if (grouped.isEmpty()) {
            for (TrendRow row : componentLess) {
                RunnerType environment =
                        definition.environmentSensitive() ? row.runnerType() : null;
                grouped.computeIfAbsent(new Key(null, environment), k -> new ArrayList<>())
                        .add(row);
            }
            return;
        }
        for (TrendRow row : componentLess) {
            grouped.forEach((key, points) -> {
                // 計測環境で分ける指標では、環境の一致する系列にだけ配る
                if (!definition.environmentSensitive()
                        || key.runnerType() == row.runnerType()) {
                    points.add(row);
                }
            });
        }
    }

    /**
     * 判定に使われた系列か。
     *
     * <p>1 点でも判定に使われていれば判定系列とみなす。全点が参考値
     * （{@code REFERENCE}）の系列だけを参考値扱いにする。判定された点を
     * 参考値の系列に混ぜると、合否の根拠がグラフから消える。
     */
    private static boolean judged(List<TrendResponse.TrendPoint> points) {
        return points.stream().anyMatch(p -> p.status() != MeasurementStatus.REFERENCE);
    }

    private static TrendResponse.TrendPoint pointOf(TrendRow row) {
        return new TrendResponse.TrendPoint(row.runId(), row.commitSha(), row.measuredAt(),
                row.value(), row.status());
    }

    private static String unitOf(List<TrendRow> rows) {
        return rows.stream().map(TrendRow::unit).filter(Objects::nonNull)
                .reduce((first, last) -> last).orElse(null);
    }

    /**
     * 重畳表示する合格ライン。期間内で最後に適用された値を返す。
     *
     * <p>しきい値は設定で変えられるため、期間内で一定とは限らない。過去の点は
     * 当時のしきい値で判定されており、1 本の線は厳密には正しくない。
     * 線は現在の基準として引き、変化があったことは {@code thresholdChanged} で示す。
     */
    private Map<String, Object> thresholdOf(List<TrendRow> rows) {
        return rows.stream().map(TrendRow::threshold).filter(Objects::nonNull)
                .reduce((first, last) -> last).map(this::toMap).orElse(null);
    }

    private static boolean thresholdChanged(List<TrendRow> rows) {
        return rows.stream().map(TrendRow::threshold).filter(Objects::nonNull)
                .distinct().count() > 1;
    }

    private Map<String, Object> toMap(String json) {
        return new LinkedHashMap<>(objectMapper.readValue(json, JSON_OBJECT));
    }

    /** 系列の同一性。 */
    private record Key(String componentName, RunnerType runnerType) implements Comparable<Key> {

        String id() {
            return (componentName == null ? "all" : componentName)
                    + (runnerType == null ? "" : "/" + runnerType.wire());
        }

        String label() {
            String base = componentName == null ? "全体" : componentName;
            if (runnerType == null) {
                return base;
            }
            String environment = runnerType == RunnerType.SELF_HOSTED
                    ? "専有ランナー" : "GitHub ホストランナー";
            return componentName == null ? environment : base + "（" + environment + "）";
        }

        @Override
        public int compareTo(Key other) {
            return id().compareTo(other.id());
        }
    }

    /** 検索期間。 */
    record Range(Instant from, Instant to) {

        static Range of(Instant from, Instant to) {
            Instant end = to == null ? Instant.now() : to;
            Instant start = from == null ? end.minus(DEFAULT_RANGE) : from;
            if (!start.isBefore(end)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "from は to より前の日時を指定してください");
            }
            if (Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "期間は最大 %d 日までです".formatted(MAX_RANGE.toDays()));
            }
            return new Range(start, end);
        }
    }
}
