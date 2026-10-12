package com.qualitygate.evaluate;

import com.qualitygate.domain.entity.Measurement;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.metric.Threshold;
import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.repo.FindingRepository;
import com.qualitygate.domain.repo.MeasurementRepository;
import com.qualitygate.domain.repo.RunRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunEvaluationServiceTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final RunRepository runs = mock(RunRepository.class);
    private final MeasurementRepository measurements = mock(MeasurementRepository.class);
    private final FindingRepository findings = mock(FindingRepository.class);

    /**
     * 判定器が {@link Threshold#withLimit} で宣言しただけの上限も、既定より緩ければ印が付く。
     * {@link Threshold} の側にも、ほかのどこにも上限のキーを登録していない。
     */
    @Test
    void 判定器が宣言しただけの上限も既定より緩いと記録する() {
        MetricEvaluator widgets = new MetricEvaluator() {
            @Override
            public String metricId() {
                return "M-06";
            }

            @Override
            public List<MetricResult> evaluate(EvaluationContext context) {
                Threshold threshold = Threshold.atMost(0).withLimit("maxWidgets", Threshold.AT_MOST,
                        "ウィジェットの上限", context.thresholds().limits().maxComplexity());
                return List.of(MetricResult.of(metricId(), "backend", MeasurementStatus.PASS, BigDecimal.ZERO,
                        "count", threshold, "判定しました", Map.of(), List.of()));
            }
        };
        Run run = EvaluatorTestSupport.run();
        when(runs.findByIdForUpdate(run.getId())).thenReturn(Optional.of(run));
        RunEvaluationService service = new RunEvaluationService(runs, measurements, findings,
                List.of(widgets), objectMapper);
        GateThresholds relaxed = EvaluatorTestSupport.thresholdsWith(Map.of(
                "complexity-max", 30,
                "disabled-metrics", GateThresholds.ALL_METRICS.stream().filter(id -> !id.equals("M-06")).toList()));

        service.evaluate(run.getId(), EvaluatorTestSupport.input(List.of(), List.of(), Set.of("M-06")), relaxed);

        Threshold saved = Threshold.parse(objectMapper, savedMeasurements().getFirst().getThreshold());
        assertThat(saved.isRelaxed()).isTrue();
        Threshold standard = saved.defaults().orElseThrow();
        assertThat(standard.extraValue("maxWidgets")).isEqualByComparingTo("15");
        assertThat(saved.looserLimitsThan(standard)).containsExactly("maxWidgets");
        assertThat(saved.limit("maxWidgets")).contains(new Threshold.Limit(Threshold.AT_MOST, "ウィジェットの上限"));
    }

    @SuppressWarnings("unchecked")
    private List<Measurement> savedMeasurements() {
        ArgumentCaptor<Iterable<Measurement>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(measurements).saveAll(captor.capture());
        List<Measurement> saved = new ArrayList<>();
        captor.getValue().forEach(saved::add);
        return saved;
    }
}
