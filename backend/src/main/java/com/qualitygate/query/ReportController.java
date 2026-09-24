package com.qualitygate.query;

import com.qualitygate.query.dto.ReportResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 品質レポート（FR-08-4）。画面（S-10）の表と、明細の CSV を返す。
 *
 * <p>PDF はサーバでは作らず、画面をブラウザの印刷（「PDF として保存」）で出す。
 * 日本語のフォントを埋め込んだ PDF をサーバで組むより、画面と同じ見た目・同じアクセシビリティで出せるため。
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports", description = "品質レポート（FR-08-4）")
public class ReportController {

    private final ReportQueryService reports;

    public ReportController(ReportQueryService reports) {
        this.reports = reports;
    }

    @GetMapping
    @Operation(summary = "品質レポートを取得する",
            description = "期間内に判定された既定ブランチの Run を、リポジトリごとにまとめる。"
                    + "期間を省略すると今日までの 30 日間。リポジトリを省略すると有効なリポジトリすべて")
    public ReportResponse report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Parameter(description = "対象のリポジトリ（複数指定可）")
            List<UUID> repositoryId) {
        return reports.summary(reports.period(from, to), repositoryId);
    }

    @GetMapping(value = "/measurements.csv", produces = "text/csv")
    @Operation(summary = "品質レポートの明細を CSV で取得する",
            description = "1 行が「Run × 指標（コンポーネント・計測条件）」。UTF-8（BOM つき）。条件はレポートと同じ")
    public void csv(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<UUID> repositoryId,
            HttpServletResponse response) throws IOException {
        ReportQueryService.Period period = reports.period(from, to);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("quality-report_%s_%s.csv".formatted(period.from(), period.to()))
                .build().toString());
        Writer writer = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
        reports.writeCsv(period, repositoryId, writer);
    }
}
