package com.qualitygate.adapter;

import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawMeasurement;
import com.qualitygate.domain.report.ReferenceValues;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * jscpd の JSON レポート（{@code jscpd-report.json}）から M-15（コード重複率）を読む。
 *
 * <p>重複率はレポートの {@code percentage} ではなく、<strong>行数から計算し直す</strong>
 * （{@code duplicatedLines / lines × 100}）。同じコンポーネントのレポートが複数届いたときに、
 * 割合どうしを平均せず行数で合算できるようにするため（M-11 の件数の合算と同じ考え方）。
 */
@Component
public class JscpdJsonAdapter implements ArtifactAdapter {

    private final ObjectMapper objectMapper;

    public JscpdJsonAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ArtifactType type) {
        return type == ArtifactType.JSCPD_JSON;
    }

    @Override
    public NormalizedReport parse(InputStream in, ParseContext context) {
        JsonNode total = read(in).path("statistics").path("total");
        if (!total.path("lines").isNumber() || !total.path("duplicatedLines").isNumber()) {
            throw new ArtifactFormatException(
                    "jscpd のレポートではありません（statistics.total.lines / duplicatedLines がありません）。"
                            + " jscpd --reporters json の jscpd-report.json を送信してください");
        }
        long lines = total.path("lines").asLong();
        long duplicated = total.path("duplicatedLines").asLong();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("lines", lines);
        detail.put("duplicatedLines", duplicated);
        detail.put("clones", total.path("clones").asLong(0));
        detail.put("sources", total.path("sources").asLong(0));
        return NormalizedReport.of(ArtifactType.JSCPD_JSON, List.of(RawMeasurement.of("M-15",
                context.componentName(), ReferenceValues.percentage(duplicated, lines), "percent", detail)), List.of());
    }

    private JsonNode read(InputStream in) {
        try {
            return objectMapper.readTree(in);
        } catch (JacksonException e) {
            throw new ArtifactFormatException("jscpd の JSON を解析できませんでした: " + e.getMessage(), e);
        }
    }
}
