package com.qualitygate.admin;

import com.qualitygate.admin.dto.RepositoryRequests.CreateRepositoryRequest;
import com.qualitygate.admin.dto.RepositoryRequests.UpdateRepositoryRequest;
import com.qualitygate.domain.entity.MonitoredRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/** リポジトリ管理（S-07）。管理者のみ。参照は RepositoryQueryController が担う。 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "RepositoryAdmin", description = "リポジトリの登録と設定（管理者のみ）")
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
    @Operation(summary = "リポジトリの設定を更新する（既定ブランチ・有効/無効）")
    public RepositoryAdminResponse update(@PathVariable UUID repositoryId,
                                          @Valid @RequestBody UpdateRepositoryRequest request) {
        return RepositoryAdminResponse.of(service.update(repositoryId, request));
    }
}
