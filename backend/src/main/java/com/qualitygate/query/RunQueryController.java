package com.qualitygate.query;

import com.qualitygate.domain.model.FindingState;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.repo.FindingCriteria;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.error.ErrorCode;
import com.qualitygate.query.dto.FindingListResponse;
import com.qualitygate.query.dto.RunDetailResponse;
import com.qualitygate.query.dto.RunListResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Run の参照 API（S-03 / S-04）。
 *
 * <p>Ingest API と同じ {@code /api/v1/runs} 配下だが、認証経路は別である。
 * 参照はセッション、取り込みは Ingest Token で、
 * 振り分けは {@code SecurityConfig.isIngestRequest} が行う。
 */
@RestController
@RequestMapping("/api/v1/runs")
@Tag(name = "Runs", description = "Run の参照")
public class RunQueryController {

    /** 既定の絞り込み。解消済みは明示的に選んだときだけ出す（docs/initial/08-screen-design.md 4.4）。 */
    private static final Set<FindingState> DEFAULT_STATES =
            Set.of(FindingState.NEW, FindingState.CONTINUING, FindingState.INITIAL);

    private final RunQueryService service;

    public RunQueryController(RunQueryService service) {
        this.service = service;
    }

    @GetMapping("/{runId}")
    @Operation(summary = "Run の判定結果を取得する",
            description = "指標はカテゴリごとにまとめて返す。分類規則を画面に持たせない。")
    public RunDetailResponse detail(@PathVariable UUID runId) {
        return service.detail(runId);
    }

    @GetMapping
    @Operation(summary = "Run を新しい順に一覧する")
    public RunListResponse list(
            @RequestParam UUID repositoryId,
            @RequestParam(required = false, defaultValue = "0") int limit,
            @RequestParam(required = false) String cursor) {
        return service.list(repositoryId, limit, cursor);
    }

    @GetMapping("/{runId}/findings")
    @Operation(summary = "Run に紐づく違反を一覧する",
            description = "state を省略すると新規・継続・初回のみを返す。解消済みは明示指定が必要。")
    public FindingListResponse findings(
            @PathVariable UUID runId,
            @Parameter(description = "指標 ID。複数指定可") @RequestParam(required = false)
            List<String> metricId,
            @Parameter(description = "省略時は NEW / CONTINUING / INITIAL")
            @RequestParam(required = false) List<String> state,
            @RequestParam(required = false) List<String> severity,
            @Parameter(description = "true=免除中のみ / false=免除でないもののみ / 省略=両方")
            @RequestParam(required = false) Boolean waived,
            @RequestParam(required = false, defaultValue = "0") int limit,
            @RequestParam(required = false) String cursor) {

        Set<FindingState> states = state == null || state.isEmpty()
                ? DEFAULT_STATES
                : parseAll(state, FindingState::valueOf, "state");

        FindingCriteria criteria = new FindingCriteria(runId,
                metricId == null ? Set.of() : Set.copyOf(metricId),
                states,
                severity == null ? Set.of() : parseAll(severity, Severity::valueOf, "severity"),
                waived);

        return service.findings(runId, criteria, limit, cursor);
    }

    /**
     * 列挙値への変換。未知の値は 400 で返す。
     *
     * <p>黙って無視すると、綴りを間違えたフィルタが「絞り込まない」として通り、
     * 利用者には全件が出たように見える。
     */
    private static <E extends Enum<E>> Set<E> parseAll(List<String> values,
                                                       Function<String, E> parser,
                                                       String parameterName) {
        return values.stream()
                .map(value -> parse(value, parser, parameterName))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static <E extends Enum<E>> E parse(String value, Function<String, E> parser,
                                               String parameterName) {
        try {
            return parser.apply(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "%s に指定できない値です: %s".formatted(parameterName, value));
        }
    }
}
