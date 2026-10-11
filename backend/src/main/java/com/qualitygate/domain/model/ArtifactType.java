package com.qualitygate.domain.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.qualitygate.domain.metric.MetricCatalog;

import java.util.List;

/**
 * 取り込む成果物の形式（docs/metrics.md 1 章）。
 *
 * <p>SARIF を静的解析系の第一形式とし、アダプタ実装を集約する。
 */
public enum ArtifactType implements WireValued {

    JACOCO_XML("jacoco-xml", MetricCatalog.M_BRANCH_COVERAGE),
    LCOV("lcov", MetricCatalog.M_BRANCH_COVERAGE),
    PIT_XML("pit-xml", MetricCatalog.M_MUTATION),
    K6_SUMMARY("k6-summary", MetricCatalog.M_PERFORMANCE_P95, MetricCatalog.M_ERROR_RATE),
    SARIF("sarif", MetricCatalog.M_VULNERABILITIES, MetricCatalog.M_COMPLEXITY,
            MetricCatalog.M_SECRETS, MetricCatalog.M_LICENSES),
    PMD_XML("pmd-xml", MetricCatalog.M_COMPLEXITY),
    ESLINT_JSON("eslint-json", MetricCatalog.M_COMPLEXITY),
    /** すべてのテストの結果（JUnit XML）。 */
    TEST_JUNIT_XML("test-junit-xml", MetricCatalog.M_TEST_SUCCESS, MetricCatalog.M_SKIPPED_TESTS),
    OASDIFF_JSON("oasdiff-json", MetricCatalog.M_BREAKING_CHANGES),
    AXE_JSON("axe-json", MetricCatalog.M_ACCESSIBILITY);

    private final String wire;
    private final List<String> metricIds;

    ArtifactType(String wire, String... metricIds) {
        this.wire = wire;
        this.metricIds = List.of(metricIds);
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static ArtifactType fromWire(String wire) {
        return WireValued.fromWire(ArtifactType.class, wire);
    }

    /** この形式が供給しうる指標 ID。 */
    public List<String> metricIds() {
        return metricIds;
    }

    /** 性能計測の成果物か（environment メタデータが必須になる）。 */
    public boolean requiresEnvironmentMetadata() {
        return this == K6_SUMMARY;
    }
}
