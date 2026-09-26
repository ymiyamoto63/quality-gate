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
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lighthouse の結果 JSON（{@code lighthouse --output json}）から M-16 を読む。1 ファイルが 1 画面・1 回分。
 *
 * <p>スコアは 0〜1 の値を 100 倍して 0〜100 で持つ（Lighthouse の画面の表示と同じ）。
 * 同じ画面の複数回分の中央値は評価器が取る。Lighthouse の値は 1 回ごとの揺れが大きいため。
 *
 * <p>画面は {@code requestedUrl} のパス（{@code /login} など）で同定する。ホストとポートは計測のたびに
 * 変わりうるため含めない。
 */
@Component
public class LighthouseJsonAdapter implements ArtifactAdapter {

    /** 内訳に残す指標（監査 ID → 内訳のキー）。 */
    private static final Map<String, String> AUDITS = Map.of(
            "largest-contentful-paint", "lcpMs",
            "cumulative-layout-shift", "cls",
            "total-blocking-time", "tbtMs",
            "first-contentful-paint", "fcpMs");

    private final ObjectMapper objectMapper;

    public LighthouseJsonAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ArtifactType type) {
        return type == ArtifactType.LIGHTHOUSE_JSON;
    }

    @Override
    public NormalizedReport parse(InputStream in, ParseContext context) {
        JsonNode root = read(in);
        JsonNode error = root.path("runtimeError");
        if (error.isObject() && !error.path("code").asString("").isEmpty()) {
            throw new ArtifactFormatException("Lighthouse の計測に失敗しています（%s）: %s"
                    .formatted(error.path("code").asString(), error.path("message").asString("")));
        }
        JsonNode performance = root.path("categories").path("performance").path("score");
        if (!performance.isNumber()) {
            throw new ArtifactFormatException(
                    "Lighthouse の結果ではありません（categories.performance.score がありません）。"
                            + " lighthouse --output json の出力を、performance を含めて送信してください");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("page", pageOf(root.path("requestedUrl").asString("")));
        detail.put("performance", score(performance));
        scoreOf(root, "accessibility").ifPresent(v -> detail.put("accessibility", v));
        scoreOf(root, "best-practices").ifPresent(v -> detail.put("bestPractices", v));
        scoreOf(root, "seo").ifPresent(v -> detail.put("seo", v));
        AUDITS.forEach((audit, key) -> {
            JsonNode value = root.path("audits").path(audit).path("numericValue");
            if (value.isNumber()) {
                detail.put(key, value.decimalValue().setScale(key.equals("cls") ? 3 : 0, RoundingMode.HALF_UP));
            }
        });
        String formFactor = root.path("configSettings").path("formFactor").asString("");
        if (!formFactor.isEmpty()) {
            detail.put("formFactor", formFactor);
        }
        String version = root.path("lighthouseVersion").asString("");
        if (!version.isEmpty()) {
            detail.put("lighthouseVersion", version);
        }
        return NormalizedReport.of(ArtifactType.LIGHTHOUSE_JSON, List.of(RawMeasurement.of("M-16",
                context.componentName(), score(performance), "score", detail)), List.of());
    }

    private static java.util.Optional<BigDecimal> scoreOf(JsonNode root, String category) {
        JsonNode node = root.path("categories").path(category).path("score");
        return node.isNumber() ? java.util.Optional.of(score(node)) : java.util.Optional.empty();
    }

    private static BigDecimal score(JsonNode node) {
        return node.decimalValue().multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP);
    }

    static String pageOf(String url) {
        try {
            String path = new URI(url).getPath();
            return path == null || path.isEmpty() ? "/" : path;
        } catch (URISyntaxException e) {
            return url;
        }
    }

    private JsonNode read(InputStream in) {
        try {
            return objectMapper.readTree(in);
        } catch (JacksonException e) {
            throw new ArtifactFormatException("Lighthouse の JSON を解析できませんでした: " + e.getMessage(), e);
        }
    }
}
