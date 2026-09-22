package com.qualitygate.domain.report;

/**
 * fingerprint を付与した違反。
 *
 * @param fingerprint Run をまたいで同一と見なすキー（sha256）
 */
public record IdentifiedFinding(String fingerprint, RawFinding finding) {

    public String metricId() {
        return finding.metricId();
    }
}
