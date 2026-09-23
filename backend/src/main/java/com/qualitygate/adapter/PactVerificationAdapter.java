package com.qualitygate.adapter;

import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.report.ContractTally;
import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawFinding;
import com.qualitygate.domain.report.RawMeasurement;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pact のプロバイダ検証の結果 JSON から M-08（API 契約テスト成功率）を読む。
 *
 * <p>受け付ける形は 2 通り。インタラクション 1 件を契約テスト 1 件と数える。
 * <ul>
 *   <li>Pact Broker に送る検証結果（pact-js / pact-go / pact-ruby の {@code publishVerificationResult}）:
 *       {@code {"success": true, "testResults": [{"interactionId": "...", "success": true, "mismatches": [...]}]}}</li>
 *   <li>pact-jvm の JSON レポート（{@code pact.verifier.reports=json}）:
 *       {@code {"provider": {...}, "execution": [{"consumer": {...}, "interactions":
 *       [{"interaction": {"description": "..."}, "verification": {"result": "OK"}}]}]}}</li>
 * </ul>
 * 失敗の理由は JUnit XML と同じく違反として残す。成功率の式は {@link ContractTally} に 1 つだけ置く。
 */
@Component
public class PactVerificationAdapter implements ArtifactAdapter {

    static final String METRIC_ID = "M-08";

    private static final int MAX_MESSAGE = 512;

    private final ObjectMapper objectMapper;

    public PactVerificationAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ArtifactType type) {
        return type == ArtifactType.PACT_VERIFICATION;
    }

    @Override
    public NormalizedReport parse(InputStream in, ParseContext context) {
        JsonNode root = read(in);
        List<Interaction> interactions;
        if (root.path("testResults").isArray()) {
            interactions = fromBrokerResult(root);
        } else if (root.path("execution").isArray()) {
            interactions = fromJvmReport(root);
        } else {
            throw new ArtifactFormatException(
                    "Pact の検証結果ではありません（testResults も execution も見つかりません）。"
                            + " Pact Broker に送る検証結果か、pact-jvm の JSON レポートを送信してください");
        }

        ContractTally tally = ContractTally.EMPTY;
        List<RawFinding> findings = new ArrayList<>();
        for (Interaction interaction : interactions) {
            tally = tally.plus(interaction.passed() ? "passed" : "failed");
            if (!interaction.passed()) {
                findings.add(interaction.toFinding(context.componentName()));
            }
        }
        RawMeasurement measurement = RawMeasurement.of(METRIC_ID, context.componentName(),
                tally.successRate(), "percent", tally.toDetail());
        return NormalizedReport.of(ArtifactType.PACT_VERIFICATION, List.of(measurement), findings);
    }

    private static List<Interaction> fromBrokerResult(JsonNode root) {
        String consumer = root.path("consumerName").asString("");
        List<Interaction> interactions = new ArrayList<>();
        for (JsonNode result : root.path("testResults")) {
            String id = result.path("interactionId").asString("");
            String description = result.path("interactionDescription").asString(
                    result.path("description").asString(id));
            boolean passed = result.path("success").asBoolean(false);
            List<String> reasons = new ArrayList<>();
            result.path("mismatches").forEach(m -> reasons.add(textOf(m)));
            result.path("exceptions").forEach(e -> reasons.add(textOf(e)));
            interactions.add(new Interaction(consumer, description, id, passed, String.join(" / ", reasons)));
        }
        return interactions;
    }

    private static List<Interaction> fromJvmReport(JsonNode root) {
        List<Interaction> interactions = new ArrayList<>();
        for (JsonNode execution : root.path("execution")) {
            String consumer = execution.path("consumer").path("name").asString("");
            for (JsonNode entry : execution.path("interactions")) {
                String description = entry.path("interaction").path("description").asString("");
                JsonNode verification = entry.path("verification");
                String result = verification.path("result").asString("");
                boolean passed = "OK".equals(result.toUpperCase(Locale.ROOT));
                String reason = passed ? "" : firstNonBlank(
                        verification.path("message").asString(""),
                        textOf(verification.path("failures")),
                        result);
                interactions.add(new Interaction(consumer, description, "", passed, reason));
            }
        }
        return interactions;
    }

    private static String textOf(JsonNode node) {
        if (node.isString()) {
            return node.asString();
        }
        for (String key : List.of("description", "message", "mismatch", "error")) {
            if (node.path(key).isString()) {
                return node.path(key).asString();
            }
        }
        return node.isMissingNode() || node.isNull() ? "" : node.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private JsonNode read(InputStream in) {
        try {
            JsonNode root = objectMapper.readTree(in);
            if (root == null || !root.isObject()) {
                throw new ArtifactFormatException("Pact の検証結果が空、または JSON オブジェクトではありません");
            }
            return root;
        } catch (JacksonException e) {
            throw new ArtifactFormatException(
                    "Pact の検証結果を JSON として解析できませんでした: " + e.getMessage(), e);
        }
    }

    private record Interaction(String consumer, String description, String id, boolean passed, String reason) {

        RawFinding toFinding(String componentName) {
            String label = consumer.isBlank() ? description : consumer + ": " + description;
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("consumer", consumer);
            detail.put("interaction", description);
            if (!id.isBlank()) {
                detail.put("interactionId", id);
            }
            detail.put("outcome", "failed");
            if (!reason.isBlank()) {
                detail.put("message", reason.length() <= MAX_MESSAGE ? reason : reason.substring(0, MAX_MESSAGE) + "…");
            }
            // 名寄せは（コンシューマ, インタラクションの説明）。ID はブローカーが振り直すことがあるため使わない
            return new RawFinding(METRIC_ID, "failed", Severity.HIGH, label + " の検証に失敗しました",
                    null, null, componentName, "pact|" + consumer + "#" + description, detail);
        }
    }
}
