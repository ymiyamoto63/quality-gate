package com.qualitygate.platform.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 生成される OpenAPI 仕様のメタ情報。
 *
 * <p>ここで生成された {@code api/openapi.yml} が、フロントエンドの型生成
 * （openapi-typescript）と M-08（破壊的変更の検出）の入力になる。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI qualityGateOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("quality-gate API")
                        .version("v1")
                        .description("""
                                リポジトリの品質指標を計測・判定・可視化する API。

                                認証は 2 経路ある。
                                - 参照・操作 API: GitHub OAuth ログイン後のセッション Cookie
                                - Ingest API: リポジトリ単位の Ingest Token（書き込み専用）
                                """)
                        .license(new License().name("Proprietary")))
                .servers(List.of(new Server().url("/").description("同一オリジン")));
    }
}
