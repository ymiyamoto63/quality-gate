package com.qualitygate.domain.report;

import com.qualitygate.domain.model.Severity;

import java.util.Map;

/**
 * アダプタが読み取った個別の違反。
 *
 * <p>{@code fingerprint} はアダプタではなく {@code normalize} モジュールが付与する。
 * 指標ごとの fingerprint 定義を 1 箇所に集約し、アダプタごとに実装がぶれないようにするため。
 */
public record RawFinding(
        String metricId,
        String ruleId,
        Severity severity,
        String title,
        String filePath,
        Integer line,
        String componentName,
        /** fingerprint の材料。行番号は含めない（無関係な編集による行ずれで誤判定しないため）。 */
        String identity,
        Map<String, Object> detail) {
}
