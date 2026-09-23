package com.qualitygate.normalize;

import com.qualitygate.adapter.ArtifactAdapter;
import com.qualitygate.adapter.ArtifactFormatException;
import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.report.IdentifiedFinding;
import com.qualitygate.domain.report.NormalizedInput;
import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawFinding;
import com.qualitygate.domain.report.RawMeasurement;
import com.qualitygate.platform.storage.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 取り込んだ成果物をアダプタに渡し、正規化・名寄せして判定エンジンへの入力を作る。
 *
 * <p>パースはトランザクションの外で行う。DB のトランザクションを
 * ファイル読み取りの間ずっと保持しないため。
 */
@Service
public class ReportNormalizer {

    private static final Logger log = LoggerFactory.getLogger(ReportNormalizer.class);

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    private final List<ArtifactAdapter> adapters;
    private final ArtifactStore artifactStore;
    private final ObjectMapper objectMapper;

    public ReportNormalizer(List<ArtifactAdapter> adapters, ArtifactStore artifactStore,
                            ObjectMapper objectMapper) {
        this.adapters = adapters;
        this.artifactStore = artifactStore;
        this.objectMapper = objectMapper;
    }

    public NormalizedInput normalize(List<ArtifactRecord> artifacts, List<String> exclusions) {
        List<RawMeasurement> measurements = new ArrayList<>();
        Map<String, IdentifiedFinding> headFindings = new LinkedHashMap<>();
        Map<String, IdentifiedFinding> baseFindings = new LinkedHashMap<>();
        Set<String> metricsWithData = new java.util.LinkedHashSet<>();
        Map<String, String> parseErrors = new HashMap<>();

        for (ArtifactRecord artifact : artifacts) {
            if (artifact.getType().isConfiguration()) {
                // 設定ファイルは指標を運ばない（設定の解決で読む）。解析の失敗として WARN を残さない
                continue;
            }
            ParseContext context = new ParseContext(artifact.getComponentName(),
                    artifact.getScope(), exclusions, metadataOf(artifact));
            try {
                NormalizedReport report = parse(artifact, context);
                measurements.addAll(report.measurements());
                collect(report.findings(), context.isBaseScope() ? baseFindings : headFindings);
                if (!context.isBaseScope()) {
                    metricsWithData.addAll(artifact.getType().metricIds());
                }
            } catch (ArtifactFormatException e) {
                // 形式不正は再実行しても直らない。当該指標を ERROR とし、理由を残す。
                log.warn("成果物の解析に失敗しました artifactId={} type={} reason={}",
                        artifact.getId(), artifact.getType().wire(), e.getMessage());
                artifact.getType().metricIds()
                        .forEach(metricId -> parseErrors.putIfAbsent(metricId, e.getMessage()));
            }
        }

        return new NormalizedInput(measurements,
                List.copyOf(headFindings.values()), List.copyOf(baseFindings.values()),
                Set.copyOf(metricsWithData), Map.copyOf(parseErrors));
    }

    /**
     * fingerprint をキーに名寄せする。複数のツールが同じ問題を報告しても 1 件にまとまる
     * （docs/initial/02-metrics-spec.md 0.4）。
     */
    private static void collect(List<RawFinding> findings, Map<String, IdentifiedFinding> into) {
        for (RawFinding finding : findings) {
            String fingerprint = Fingerprints.of(finding);
            into.putIfAbsent(fingerprint, new IdentifiedFinding(fingerprint, finding));
        }
    }

    private NormalizedReport parse(ArtifactRecord artifact, ParseContext context) {
        ArtifactAdapter adapter = adapterFor(artifact)
                .orElseThrow(() -> new ArtifactFormatException(
                        "この形式のアダプタが未実装です: " + artifact.getType().wire()));
        try (InputStream in = artifactStore.open(artifact.getStorageKey())) {
            return adapter.parse(in, context);
        } catch (java.io.IOException e) {
            throw new ArtifactFormatException(
                    "成果物を読み出せませんでした: " + artifact.getFilename(), e);
        }
    }

    /**
     * 取り込み時に JSON オブジェクトであることを検証済みのため、通常は失敗しない。
     * 検証の導入前に取り込まれた成果物に備え、読めなければメタデータ無しとして扱う。
     */
    private Map<String, Object> metadataOf(ArtifactRecord artifact) {
        String metadata = artifact.getMetadata();
        if (metadata == null || metadata.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadata, JSON_OBJECT);
        } catch (JacksonException e) {
            log.warn("成果物のメタデータを読めませんでした artifactId={}", artifact.getId());
            return Map.of();
        }
    }

    private Optional<ArtifactAdapter> adapterFor(ArtifactRecord artifact) {
        return adapters.stream().filter(a -> a.supports(artifact.getType())).findFirst();
    }
}
