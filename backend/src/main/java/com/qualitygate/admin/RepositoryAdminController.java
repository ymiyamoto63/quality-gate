package com.qualitygate.admin;

import com.qualitygate.admin.dto.IngestTokenResponses;
import com.qualitygate.admin.dto.RepositoryRequests.CreateRepositoryRequest;
import com.qualitygate.admin.dto.RepositoryRequests.IssueTokenRequest;
import com.qualitygate.admin.dto.RepositoryRequests.UpdateRepositoryRequest;
import com.qualitygate.domain.entity.MonitoredRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/** リポジトリ管理（S-08）。管理者のみ。参照は RepositoryQueryController が担う。 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "RepositoryAdmin", description = "リポジトリ登録・コンポーネント・Ingest Token（管理者のみ）")
public class RepositoryAdminController {

    private final RepositoryAdminService service;

    public RepositoryAdminController(RepositoryAdminService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/repositories")
    @Operation(summary = "リポジトリを登録する")
    public ResponseEntity<RepositoryAdminResponse> create(
            @Valid @RequestBody CreateRepositoryRequest request) {
        MonitoredRepository repository = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/repositories/" + repository.getId()))
                .body(RepositoryAdminResponse.of(repository));
    }

    @PatchMapping("/api/v1/repositories/{repositoryId}")
    @Operation(summary = "リポジトリの設定を更新する（既定ブランチ・PR 計測・有効/無効）")
    public RepositoryAdminResponse update(@PathVariable UUID repositoryId,
                                          @Valid @RequestBody UpdateRepositoryRequest request) {
        return RepositoryAdminResponse.of(service.update(repositoryId, request));
    }

    @GetMapping("/api/v1/repositories/{repositoryId}/ingest-tokens")
    @Operation(summary = "発行済みの Ingest Token を一覧する（平文は返さない）")
    public IngestTokenResponses.TokenList tokens(@PathVariable UUID repositoryId) {
        return new IngestTokenResponses.TokenList(service.tokensOf(repositoryId).stream()
                .map(IngestTokenResponses.TokenSummary::of).toList());
    }

    @PostMapping("/api/v1/repositories/{repositoryId}/ingest-tokens")
    @Operation(summary = "Ingest Token を発行する",
            description = "token（平文）はこの応答でのみ返す。以後どの API からも取得できない。")
    public ResponseEntity<IngestTokenResponses.IssuedToken> issue(
            @PathVariable UUID repositoryId, @Valid @RequestBody(required = false)
            IssueTokenRequest request) {
        RepositoryAdminService.Issued issued = service.issueToken(repositoryId,
                request == null ? null : request.description());
        return ResponseEntity.status(201).body(new IngestTokenResponses.IssuedToken(
                issued.token().getId(), issued.plainToken(), issued.token().getTokenPrefix(),
                issued.token().getCreatedAt()));
    }

    @DeleteMapping("/api/v1/ingest-tokens/{tokenId}")
    @Operation(summary = "Ingest Token を失効させる（即座に無効になる）")
    public ResponseEntity<Void> revoke(@PathVariable UUID tokenId) {
        service.revokeToken(tokenId);
        return ResponseEntity.noContent().build();
    }
}
