package com.qualitygate.release;

import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.platform.config.QualityGateProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 判定の履歴と指標の推移が共通で使う、判定済みの Run の選び方。
 *
 * <p>2 つの画面で数え方がずれると、履歴にある計測が推移に無い（またはその逆）ことになるため、ここ 1 箇所で決める。
 */
@Component
class EvaluatedRuns {

    private final MonitoredRepositoryRepository repositories;
    private final RunRepository runs;
    private final QualityGateProperties properties;

    EvaluatedRuns(MonitoredRepositoryRepository repositories, RunRepository runs, QualityGateProperties properties) {
        this.repositories = repositories;
        this.runs = runs;
        this.properties = properties;
    }

    /** 計測対象（{@code QG_REPOSITORY}）。まだ 1 度も計測を受け取っていなければ空。 */
    Optional<MonitoredRepository> repository() {
        String fullName = properties.repository();
        int slash = fullName.indexOf('/');
        return repositories.findByOwnerAndName(fullName.substring(0, slash), fullName.substring(slash + 1));
    }

    /**
     * 判定済みの Run を新しい順に最大 {@code limit} 件。同じコミットを何度か計測していれば、最後の判定だけを返す。
     * ブランチでは絞らない。
     */
    List<Run> latestPerCommit(MonitoredRepository repository, int limit) {
        // 同じコミットの計測し直しで件数が減る分を見込んで多めに読む
        List<Run> evaluated = runs.findByRepositoryIdAndStatusOrderByMeasuredAtDescAttemptDesc(
                repository.getId(), RunStatus.EVALUATED, PageRequest.of(0, limit * 4));
        Set<String> seen = new HashSet<>();
        return evaluated.stream()
                .filter(run -> seen.add(run.getCommitSha()))
                .limit(limit)
                .toList();
    }
}
