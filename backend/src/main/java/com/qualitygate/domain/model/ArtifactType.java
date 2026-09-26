package com.qualitygate.domain.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;

/**
 * 取り込む成果物の形式（docs/initial/02-metrics-spec.md 0.5）。
 *
 * <p>SARIF を静的解析系の第一形式とし、アダプタ実装を集約する。
 */
public enum ArtifactType implements WireValued {

    JACOCO_XML("jacoco-xml", "M-01"),
    LCOV("lcov", "M-01"),
    PIT_XML("pit-xml", "M-02"),
    K6_SUMMARY("k6-summary", "M-03", "M-04", "M-05"),
    SARIF("sarif", "M-06", "M-07", "M-13", "M-14"),
    PMD_XML("pmd-xml", "M-07"),
    ESLINT_JSON("eslint-json", "M-07"),
    /** すべてのテストの結果（JUnit XML）。 */
    TEST_JUNIT_XML("test-junit-xml", "M-11", "M-12"),
    OASDIFF_JSON("oasdiff-json", "M-09"),
    AXE_JSON("axe-json", "M-10"),
    /** jscpd の JSON レポート（{@code jscpd-report.json}）。参考値の M-15。 */
    JSCPD_JSON("jscpd-json", "M-15"),
    /** Lighthouse の結果 JSON（{@code --output json}。1 画面 1 回分）。参考値の M-16。 */
    LIGHTHOUSE_JSON("lighthouse-json", "M-16"),
    /**
     * ビルドした画面のファイルサイズ（収集ランナーの {@code collector/bundle/size.mjs} の出力）。参考値の M-17。
     * 形式: {@code {"files": [{"path": "assets/index.js", "bytes": 1234, "gzipBytes": 567}]}}
     */
    BUNDLE_SIZE_JSON("bundle-size-json", "M-17"),

    /**
     * リポジトリの {@code .quality-gate.yml} そのもの。
     *
     * <p>指標を供給しないが、判定に使う合格ラインを運ぶ。
     */
    QUALITY_GATE_CONFIG("quality-gate-config");

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

    /** 指標の計測結果ではなく、判定の設定を運ぶ成果物か。 */
    public boolean isConfiguration() {
        return this == QUALITY_GATE_CONFIG;
    }
}
