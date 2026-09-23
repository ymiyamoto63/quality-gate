package com.qualitygate.adapter;

import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawMeasurement;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * istanbul の coverage-final.json から M-01（ブランチカバレッジ）を読む。
 * Vitest（istanbul / v8）と Jest の {@code json} reporter の出力が対象。
 *
 * <p>ファイルのパスをキーにしたオブジェクトで、各ファイルの {@code b} が
 * 「分岐 ID → 各経路の実行回数の配列」を持つ。経路 1 つを分岐 1 本と数え、
 * 実行回数が 1 以上の経路を「実行された分岐」とする（lcov の BRF / BRH と同じ数え方）。
 */
@Component
public class IstanbulJsonAdapter implements ArtifactAdapter {

    private final ObjectMapper objectMapper;

    public IstanbulJsonAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ArtifactType type) {
        return type == ArtifactType.ISTANBUL_JSON;
    }

    @Override
    public NormalizedReport parse(InputStream in, ParseContext context) {
        JsonNode root = read(in);
        long found = 0;
        long hit = 0;
        int files = 0;
        int excludedFiles = 0;

        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            JsonNode file = entry.getValue();
            JsonNode branches = file.path("b");
            if (!file.isObject() || !branches.isObject()) {
                throw new ArtifactFormatException(
                        "istanbul の coverage-final.json ではありません（%s に b がありません）。"
                                .formatted(entry.getKey())
                                + " json reporter の coverage-final.json を送信してください");
            }
            files++;
            String path = file.path("path").asString(entry.getKey());
            if (context.isExcluded(path)) {
                excludedFiles++;
                continue;
            }
            for (Map.Entry<String, JsonNode> branch : branches.properties()) {
                if (!branch.getValue().isArray()) {
                    throw new ArtifactFormatException(
                            "分岐 %s の実行回数が配列ではありません（%s）".formatted(branch.getKey(), path));
                }
                for (JsonNode count : branch.getValue()) {
                    found++;
                    if (count.asLong(0) > 0) {
                        hit++;
                    }
                }
            }
        }

        if (files == 0) {
            throw new ArtifactFormatException(
                    "coverage-final.json にファイルがありません。カバレッジが 1 ファイルも計測されていないか、"
                            + " 別のファイルが送信されています");
        }

        BigDecimal value = found == 0
                ? null
                : BigDecimal.valueOf(hit * 100L)
                        .divide(BigDecimal.valueOf(found), 4, RoundingMode.HALF_UP);

        return NormalizedReport.of(ArtifactType.ISTANBUL_JSON,
                List.of(RawMeasurement.of("M-01", context.componentName(), value, "percent", Map.of(
                        "coveredBranches", hit,
                        "totalBranches", found,
                        "files", files,
                        "excludedFiles", excludedFiles))),
                List.of());
    }

    private JsonNode read(InputStream in) {
        try {
            JsonNode root = objectMapper.readTree(in);
            if (root == null || !root.isObject()) {
                throw new ArtifactFormatException(
                        "coverage-final.json が空、または JSON オブジェクトではありません");
            }
            return root;
        } catch (JacksonException e) {
            throw new ArtifactFormatException(
                    "coverage-final.json を JSON として解析できませんでした: " + e.getMessage(), e);
        }
    }
}
