package com.qualitygate.query;

import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.RepositorySummary;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RepositorySummaryRepository;
import com.qualitygate.query.dto.DashboardResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard", description = "全リポジトリのサマリ")
public class DashboardController {

    private final MonitoredRepositoryRepository repositories;
    private final RepositorySummaryRepository summaries;

    public DashboardController(MonitoredRepositoryRepository repositories,
                               RepositorySummaryRepository summaries) {
        this.repositories = repositories;
        this.summaries = summaries;
    }

    @GetMapping
    @Operation(summary = "ダッシュボードを取得する",
            description = "不合格・注意を先頭に並べる。開く目的が「問題があるか確認すること」であるため。")
    @Transactional(readOnly = true)
    public DashboardResponse dashboard() {
        List<DashboardResponse.RepositoryCard> cards =
                repositories.findByEnabledTrueOrderByOwnerAscNameAsc().stream()
                        .map(this::toCard)
                        .sorted(Comparator.comparingInt(DashboardController::severityOrder))
                        .toList();

        return new DashboardResponse(cards);
    }

    private DashboardResponse.RepositoryCard toCard(MonitoredRepository repo) {
        Optional<RepositorySummary> summary = summaries.findById(repo.getId());

        DashboardResponse.LatestRun latestRun = summary
                .filter(s -> s.getLatestRunId() != null)
                .map(s -> new DashboardResponse.LatestRun(s.getLatestRunId(), s.getLatestVerdict(),
                        s.getLatestCompleteness(), s.getLatestMeasuredAt()))
                .orElse(null);

        return new DashboardResponse.RepositoryCard(
                repo.getId(), repo.fullName(), latestRun,
                summary.map(RepositorySummary::getOpenCriticalCount).orElse(0),
                summary.map(RepositorySummary::getOpenHighCount).orElse(0),
                new DashboardResponse.Freshness(
                        summary.map(RepositorySummary::getLatestMeasuredAt).orElse(null),
                        summary.map(RepositorySummary::getLastFullMeasuredAt).orElse(null)));
    }

    /** 不合格 → 注意 → 合格 → 未判定 の順に並べる。 */
    private static int severityOrder(DashboardResponse.RepositoryCard card) {
        if (card.latestRun() == null || card.latestRun().verdict() == null) {
            return 3;
        }
        return switch (card.latestRun().verdict()) {
            case FAIL -> 0;
            case PASS_WITH_WARNINGS -> 1;
            case PASS -> 2;
        };
    }
}
