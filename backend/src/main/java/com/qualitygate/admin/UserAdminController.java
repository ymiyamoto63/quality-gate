package com.qualitygate.admin;

import com.qualitygate.admin.dto.CreateUserRequest;
import com.qualitygate.admin.dto.UpdateUserRequest;
import com.qualitygate.admin.dto.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/** 許可リスト（S-08 利用者）。管理者のみ。 */
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Users", description = "許可リストとロールの管理（管理者のみ）")
public class UserAdminController {

    private final UserAdminService service;

    public UserAdminController(UserAdminService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "許可リストの利用者を一覧する")
    public UserResponse.UserList list() {
        return new UserResponse.UserList(service.list().stream().map(UserResponse::of).toList());
    }

    @PostMapping
    @Operation(summary = "許可リストに利用者を追加する",
            description = "GitHub ログイン名の実在は確認しない。存在しない名前はログインできないだけで害がない。")
    public ResponseEntity<UserResponse> add(@Valid @RequestBody CreateUserRequest request) {
        UserResponse created = UserResponse.of(service.add(request));
        return ResponseEntity.created(URI.create("/api/v1/users/" + created.userId()))
                .body(created);
    }

    @PatchMapping("/{userId}")
    @Operation(summary = "ロールの変更・無効化",
            description = "自分自身の降格・無効化と、有効な管理者が 0 人になる変更は 409 ADMIN_REQUIRED。")
    public UserResponse update(@PathVariable UUID userId,
                               @RequestBody UpdateUserRequest request) {
        return UserResponse.of(service.update(userId, request));
    }
}
