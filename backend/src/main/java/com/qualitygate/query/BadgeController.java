package com.qualitygate.query;

import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RunRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * README に埋め込むバッジ（FR-08-5。docs/initial/07-api-design.md 2.4）。
 *
 * <p>README の画像はブラウザ（GitHub の画像プロキシ）が Cookie なしで取りに来るため、認証しない。
 * 返すのは既定ブランチの最新の判定の合否だけで、指標値や違反の内容は含めない。
 * 登録されていない・無効化されたリポジトリも「unknown」を返し、どのリポジトリが登録されているかを
 * 外から見分けられないようにする。
 */
@RestController
@Tag(name = "Badge", description = "README 用のバッジ")
public class BadgeController {

    /** GitHub の画像プロキシがキャッシュしすぎないよう、短めにする。 */
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    private static final MediaType SVG = MediaType.valueOf("image/svg+xml;charset=UTF-8");

    private final MonitoredRepositoryRepository repositories;
    private final RunRepository runs;

    public BadgeController(MonitoredRepositoryRepository repositories, RunRepository runs) {
        this.repositories = repositories;
        this.runs = runs;
    }

    @GetMapping(value = "/badges/{owner}/{name}.svg", produces = "image/svg+xml")
    @Operation(summary = "既定ブランチの最新の合否のバッジ（認証不要）")
    @Transactional(readOnly = true)
    public ResponseEntity<String> badge(@PathVariable String owner, @PathVariable String name) {
        BadgeSvg.Status status = repositories.findByOwnerAndName(owner, name)
                .filter(MonitoredRepository::isEnabled)
                .flatMap(repository -> runs.findFirstByRepositoryIdAndBranchAndStatusOrderByMeasuredAtDesc(
                        repository.getId(), repository.getDefaultBranch(), RunStatus.EVALUATED))
                .map(BadgeController::statusOf)
                .orElse(BadgeSvg.Status.UNKNOWN);
        return ResponseEntity.ok()
                .contentType(SVG)
                .cacheControl(CACHE)
                .body(BadgeSvg.render(status));
    }

    private static BadgeSvg.Status statusOf(Run run) {
        if (run.getVerdict() == null) {
            return BadgeSvg.Status.UNKNOWN;
        }
        return switch (run.getVerdict()) {
            case PASS -> BadgeSvg.Status.PASSING;
            case PASS_WITH_WARNINGS -> BadgeSvg.Status.WARNINGS;
            case FAIL -> BadgeSvg.Status.FAILING;
        };
    }
}
