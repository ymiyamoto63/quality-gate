package com.qualitygate.release;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * リリース判定（画面はこれ 1 つ）。PDF はサーバでは作らず、画面をブラウザの印刷で出す。
 */
@RestController
@RequestMapping("/api/v1/release")
@Tag(name = "Release", description = "リリース判定")
public class ReleaseReportController {

    private final ReleaseReportService service;

    public ReleaseReportController(ReleaseReportService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "リリース判定を取得する",
            description = "指定したタグ・コミットで判定済みの計測から、リリースしてよいかと全指標の合否を返す。"
                    + "指定が無ければ最新の判定済みの計測を使う。ブランチ名は受け付けない")
    public ReleaseReportResponse releaseReport(
            @RequestParam(required = false) @Parameter(description = "タグ名、またはコミット SHA（7〜40 桁）。省略すると最新")
            String ref) {
        return service.report(ref);
    }

    @GetMapping("/history")
    @Operation(summary = "判定の履歴を取得する", description = "判定済みの計測を新しい順に最大 50 件（同じコミットは最後の判定だけ）")
    public ReleaseHistoryResponse history() {
        return service.history();
    }
}
