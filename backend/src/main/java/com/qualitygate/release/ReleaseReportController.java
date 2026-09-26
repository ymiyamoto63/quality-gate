package com.qualitygate.release;

import com.qualitygate.platform.security.Actor;
import com.qualitygate.platform.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

/**
 * リリース判定（UC-10 / S-11）。画面用の JSON と、証跡用の CSV を返す。
 *
 * <p>PDF はサーバでは作らず、画面をブラウザの印刷で出す（品質レポートと同じ方針）。
 */
@RestController
@RequestMapping("/api/v1/repositories/{repositoryId}")
@Tag(name = "Release", description = "リリース判定（UC-10）")
public class ReleaseReportController {

    private final ReleaseReportService service;
    private final CurrentUser currentUser;
    private final ZoneId zone;

    public ReleaseReportController(ReleaseReportService service, CurrentUser currentUser,
                                   @Value("${quality-gate.schedule.zone:Asia/Tokyo}") String zone) {
        this.service = service;
        this.currentUser = currentUser;
        this.zone = ZoneId.of(zone);
    }

    @GetMapping("/release-report")
    @Operation(summary = "リリース判定を取得する",
            description = "指定したタグ・コミットで判定済みの Run から、リリースしてよいかと全指標の合否を返す。"
                    + "タグは GitHub API でコミットに解決する。ブランチ名は受け付けない")
    public ReleaseReportResponse releaseReport(
            @PathVariable UUID repositoryId,
            @RequestParam @Parameter(description = "タグ名、またはコミット SHA（7〜40 桁）") String ref) {
        return service.report(repositoryId, ref);
    }

    @GetMapping(value = "/release-report.csv", produces = "text/csv")
    @Operation(summary = "リリース判定を CSV で取得する",
            description = "1 行が 1 指標。判定の前提（コミット・Run・合格ラインの版）と出力日時・出力者を各行に含む。"
                    + "UTF-8（BOM つき）。出力したことを監査ログに残す")
    public void releaseReportCsv(@PathVariable UUID repositoryId, @RequestParam String ref, HttpServletResponse response)
            throws IOException {
        Actor actor = currentUser.actor();
        ReleaseReportResponse report = service.reportForExport(repositoryId, ref, actor);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(ReleaseReportCsv.filename(report))
                .build().toString());
        Writer writer = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
        ReleaseReportCsv.write(report, Instant.now(), actor.login(), zone, writer);
    }
}
