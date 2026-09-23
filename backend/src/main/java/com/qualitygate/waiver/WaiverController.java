package com.qualitygate.waiver;

import com.qualitygate.domain.model.WaiverStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/** 免除管理（S-07）。閲覧は全員、登録・失効は管理者のみ。 */
@RestController
@RequestMapping("/api/v1/waivers")
@Tag(name = "Waivers", description = "免除の一覧・登録・失効")
public class WaiverController {

    private final WaiverService service;

    public WaiverController(WaiverService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "免除を一覧する", description = "有効なものを期限の近い順に先頭へ並べる。")
    public WaiverDtos.WaiverList list(
            @Parameter(description = "省略時は全リポジトリ") @RequestParam(required = false)
            UUID repositoryId,
            @Parameter(description = "省略時はすべての状態") @RequestParam(required = false)
            WaiverStatus status) {
        return service.list(repositoryId, status);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "免除を登録する",
            description = "理由は 20 文字以上、期限は最長 90 日。登録と同時に最新 Run の再評価を積む。"
                    + "同じ対象に有効な免除があれば 409 WAIVER_ALREADY_EXISTS。")
    public ResponseEntity<WaiverDtos.WaiverItem> create(
            @Valid @RequestBody WaiverDtos.CreateWaiverRequest request) {
        UUID waiverId = service.create(request).getId();
        WaiverDtos.WaiverItem created = service.list(request.repositoryId(), null).items().stream()
                .filter(item -> item.waiverId().equals(waiverId)).findFirst().orElseThrow();
        return ResponseEntity.created(URI.create("/api/v1/waivers/" + waiverId)).body(created);
    }

    @DeleteMapping("/{waiverId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "免除を失効させる", description = "失効と同時に最新 Run の再評価を積む。")
    public ResponseEntity<Void> revoke(@PathVariable UUID waiverId) {
        service.revoke(waiverId);
        return ResponseEntity.noContent().build();
    }
}
