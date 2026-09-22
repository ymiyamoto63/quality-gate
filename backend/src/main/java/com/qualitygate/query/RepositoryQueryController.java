package com.qualitygate.query;

import com.qualitygate.query.dto.TrendResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/repositories")
@Tag(name = "Repositories", description = "リポジトリ単位の参照")
public class RepositoryQueryController {

    private final TrendQueryService trends;

    public RepositoryQueryController(TrendQueryService trends) {
        this.trends = trends;
    }

    @GetMapping("/{repositoryId}/trends")
    @Operation(summary = "指標の時系列を取得する",
            description = "計測環境ごとに系列を分ける。未計測は値 null の点として返し、0 にしない。")
    public TrendResponse trends(
            @PathVariable UUID repositoryId,
            @Parameter(description = "指標 ID（M-01 など）") @RequestParam String metricId,
            @Parameter(description = "省略時はリポジトリの既定ブランチ")
            @RequestParam(required = false) String branch,
            @Parameter(description = "省略時は to の 30 日前")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "省略時は現在時刻")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return trends.trend(repositoryId, metricId, branch, from, to);
    }
}
