package com.qualitygate.release;

import com.qualitygate.domain.model.MeasurementStatus;

import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * リリース判定の CSV（証跡）。
 *
 * <p>1 行 = 1 指標の平らな表にし、判定の前提（コミット・Run・合格ラインの版・出力日時・出力者）を各行に繰り返す。
 * 見出しのブロックを別に持つ形は、Excel の並べ替えや後からの集計で扱いにくい。
 * 読み手に経営陣を想定するため、列名と値は日本語にする。Excel で開けるよう UTF-8 の BOM を付ける。
 */
final class ReleaseReportCsv {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    static final List<String> HEADER = List.of("リポジトリ", "指定", "コミット", "リリース判定", "判定の理由",
            "Run ID", "計測日時", "計測範囲", "合格ラインの版", "除外パターン", "指標ID", "指標", "カテゴリ",
            "コンポーネント", "計測条件", "値", "しきい値", "判定", "判定の詳細", "何を見る指標か", "根拠の種類",
            "基準の根拠", "計測ツール", "出力日時", "出力者");

    private ReleaseReportCsv() {
    }

    /** 例: {@code release_owner_repo_v1.2.0_a1b2c3d.csv}。コミットで指定したときはタグの部分を持たない。 */
    static String filename(ReleaseReportResponse report) {
        String repository = report.repositoryFullName().replace('/', '_');
        String shortSha = report.commitSha().substring(0, Math.min(7, report.commitSha().length()));
        if (report.refType() == ReleaseRefResolver.RefType.COMMIT) {
            return "release_%s_%s.csv".formatted(repository, shortSha);
        }
        // ファイル名に使えない文字（/ など）は _ にする
        return "release_%s_%s_%s.csv".formatted(repository, report.ref().replaceAll("[^0-9A-Za-z._-]", "_"),
                shortSha);
    }

    static void write(ReleaseReportResponse report, Instant exportedAt, String exportedBy, ZoneId zone, Writer out)
            throws IOException {
        Map<String, ReleaseReportResponse.ReleaseGuide> guides = report.guides().stream()
                .collect(Collectors.toMap(ReleaseReportResponse.ReleaseGuide::metricId, Function.identity()));
        List<String> common = List.of(report.repositoryFullName(), report.ref(), report.commitSha(),
                decisionLabel(report.decision()), report.decisionReason(),
                report.run() == null ? "" : report.run().runId().toString(),
                report.run() == null ? "" : TIMESTAMP.format(report.run().measuredAt().atZone(zone)),
                report.run() == null ? "" : completenessLabel(report.run().completeness().name()),
                report.gateConfig() == null
                        ? (report.run() == null ? "" : "既定値")
                        : "v" + report.gateConfig().version(),
                report.gateConfig() == null ? "" : String.join(" ", report.gateConfig().exclusions()));
        String exported = TIMESTAMP.format(exportedAt.atZone(zone));

        out.write('﻿');
        writeRow(out, HEADER);
        if (report.metrics().isEmpty()) {
            // 未計測でも、判定できなかったことを証跡として 1 行残す
            List<String> row = new ArrayList<>(common);
            row.addAll(List.of("", "", "", "", "", "", "", "", "", "", "", "", "", exported, exportedBy));
            writeRow(out, row);
        }
        for (ReleaseReportResponse.ReleaseMetric metric : report.metrics()) {
            ReleaseReportResponse.ReleaseGuide guide = guides.get(metric.metricId());
            List<String> row = new ArrayList<>(common);
            row.addAll(List.of(metric.metricId(), metric.name(), metric.category(),
                    text(metric.componentName()), condition(metric), value(metric), text(metric.threshold()),
                    statusLabel(metric.status()), text(metric.reason()),
                    guide == null ? "" : guide.summary(), guide == null ? "" : guide.basisLabel(),
                    guide == null ? "" : guide.rationale(), guide == null ? "" : guide.tools(),
                    exported, exportedBy));
            writeRow(out, row);
        }
        out.flush();
    }

    static String decisionLabel(ReleaseDecision decision) {
        return switch (decision) {
            case RELEASABLE -> "リリース可";
            case RELEASABLE_WITH_WARNINGS -> "リリース可（注意あり）";
            case NOT_RELEASABLE -> "リリース不可";
            case UNDETERMINED -> "判定できない";
        };
    }

    private static String completenessLabel(String completeness) {
        return "FULL".equals(completeness) ? "完全計測" : "部分計測";
    }

    /** 画面（StatusChip）と同じ語。 */
    static String statusLabel(MeasurementStatus status) {
        return switch (status) {
            case PASS -> "合格";
            case WARN -> "注意";
            case FAIL -> "不合格";
            case ERROR -> "計測エラー";
            case SKIP -> "未計測";
            case REFERENCE -> "参考値";
            case NOT_APPLICABLE -> "対象外";
        };
    }

    private static String condition(ReleaseReportResponse.ReleaseMetric metric) {
        List<String> parts = new ArrayList<>();
        if (metric.variantLabel() != null) {
            parts.add(metric.variantLabel());
        }
        if (metric.scenario() != null) {
            parts.add(metric.scenario());
        }
        return String.join(" / ", parts);
    }

    private static String value(ReleaseReportResponse.ReleaseMetric metric) {
        if (metric.value() == null) {
            return "";
        }
        return ReleaseReportService.ThresholdText.plain(metric.value())
                + ReleaseReportService.ThresholdText.suffix(metric.unit());
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static void writeRow(Writer out, List<String> values) throws IOException {
        out.write(values.stream().map(ReleaseReportCsv::escape).collect(Collectors.joining(",")));
        out.write("\r\n");
    }

    /**
     * RFC 4180 のクォートに加え、先頭が {@code = + - @} の値は {@code '} を前置する。
     * 指標の理由やパスに由来する文字列が、表計算ソフトで数式として実行されないようにする（CSV インジェクション対策）。
     */
    static String escape(String value) {
        String safe = !value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }
}
