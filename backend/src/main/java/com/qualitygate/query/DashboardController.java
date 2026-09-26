package com.qualitygate.query;

import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.model.Completeness;
import com.qualitygate.domain.model.FindingState;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.repo.FindingRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
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

    /** 脆弱性（M-06）。 */
    private static final String M_VULNERABILITIES = "M-06";

    private final MonitoredRepositoryRepository repositories;
    private final RunRepository runs;
    private final FindingRepository findings;

    public DashboardController(MonitoredRepositoryRepository repositories, RunRepository runs,
                               FindingRepository findings) {
        this.repositories = repositories;
        this.runs = runs;
        this.findings = findings;
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
        Optional<Run> latest = runs.findFirstByRepositoryIdAndStatusOrderByMeasuredAtDescAttemptDesc(
                repo.getId(), RunStatus.EVALUATED);
        Optional<Run> lastFull = runs.findFirstByRepositoryIdAndStatusAndCompletenessOrderByMeasuredAtDescAttemptDesc(
                repo.getId(), RunStatus.EVALUATED, Completeness.FULL);

        return new DashboardResponse.RepositoryCard(
                repo.getId(), repo.fullName(),
                latest.map(run -> new DashboardResponse.LatestRun(run.getId(), run.getVerdict(),
                        run.getCompleteness(), run.getMeasuredAt())).orElse(null),
                latest.map(run -> openVulnerabilities(run, Severity.CRITICAL)).orElse(0),
                latest.map(run -> openVulnerabilities(run, Severity.HIGH)).orElse(0),
                new DashboardResponse.Freshness(
                        latest.map(Run::getMeasuredAt).orElse(null),
                        lastFull.map(Run::getMeasuredAt).orElse(null)));
    }

    /**
     * 「重大 N 件・高 N 件」は脆弱性（M-06）の件数に限る。アクセシビリティ違反（M-09）も
     * 同じ深刻度で保存するため、混ぜると未解決の脆弱性が増えたように見える。
     */
    private int openVulnerabilities(Run run, Severity severity) {
        return (int) findings.countByRunIdAndMetricIdAndSeverityAndStateNot(run.getId(),
                M_VULNERABILITIES, severity, FindingState.RESOLVED);
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
