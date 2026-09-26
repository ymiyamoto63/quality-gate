package com.qualitygate.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.util.List;

/**
 * SPA のクライアントサイドルーティングを成立させる。
 *
 * <p>{@code /runs/xxx} のような URL を直接開いた場合、サーバ側にそのパスは無い。
 * 静的ファイルとして解決できないリクエストを index.html に解決し直し、
 * ルーティングを Vue Router に委ねる。
 *
 * <p>ただし API・監視・ドキュメントのパスは対象外とする。存在しない API を
 * index.html で返すと、クライアントは 200 と HTML を受け取り、
 * 「404 が返ってきた」と気づけなくなる。
 */
@Configuration
public class SpaForwardingConfig implements WebMvcConfigurer {

    private static final String INDEX = "/static/index.html";

    private static final List<String> NON_SPA_PREFIXES =
            List.of("api/", "actuator/", "v3/", "swagger-ui", "oauth2/", "login/", "logout");

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new SpaFallbackResolver());
    }

    static class SpaFallbackResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = location.createRelative(resourcePath);
            if (requested.exists() && requested.isReadable()) {
                return requested;
            }
            if (isNonSpaPath(resourcePath)) {
                return null;
            }
            Resource index = new ClassPathResource(INDEX);
            // フロントエンドを同梱せずに起動した場合（-DskipFrontend）は素直に 404 を返す。
            return index.exists() ? index : null;
        }

        private static boolean isNonSpaPath(String resourcePath) {
            return NON_SPA_PREFIXES.stream().anyMatch(resourcePath::startsWith);
        }
    }
}
