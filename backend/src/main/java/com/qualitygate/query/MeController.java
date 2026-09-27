package com.qualitygate.query;

import com.qualitygate.query.dto.MeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Me", description = "ログインの状態")
public class MeController {

    @GetMapping
    @Operation(summary = "ログイン中の利用者を返す", description = "未ログインなら 401")
    public MeResponse me(Authentication authentication) {
        return new MeResponse(authentication.getName());
    }
}
