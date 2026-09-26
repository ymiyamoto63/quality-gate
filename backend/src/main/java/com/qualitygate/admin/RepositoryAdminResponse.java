package com.qualitygate.admin;

import com.qualitygate.domain.entity.MonitoredRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "登録・更新したリポジトリ")
public record RepositoryAdminResponse(
        @NotNull UUID repositoryId,
        @NotNull String fullName,
        @NotNull String defaultBranch,
        @NotNull boolean enabled) {

    static RepositoryAdminResponse of(MonitoredRepository repository) {
        return new RepositoryAdminResponse(repository.getId(), repository.fullName(),
                repository.getDefaultBranch(), repository.isEnabled());
    }
}
