package com.qualitygate.query;

import com.qualitygate.domain.repo.UserAccountRepository;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.error.ErrorCode;
import com.qualitygate.query.dto.MeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Me", description = "ログイン中の利用者")
public class MeController {

    private final UserAccountRepository users;

    public MeController(UserAccountRepository users) {
        this.users = users;
    }

    @GetMapping
    @Operation(summary = "ログイン中の利用者とロールを返す")
    public MeResponse me(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "ログインしてください");
        }
        String login = principal.getAttribute("login");
        return users.findByGithubLoginIgnoreCase(login)
                .map(u -> new MeResponse(u.getId(), u.getGithubLogin(), u.getDisplayName(),
                        u.getAvatarUrl(), u.getRole()))
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_ALLOWLISTED,
                        "許可リストに登録されていません"));
    }
}
