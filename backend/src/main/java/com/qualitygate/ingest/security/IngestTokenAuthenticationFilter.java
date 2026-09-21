package com.qualitygate.ingest.security;

import com.qualitygate.domain.entity.IngestToken;
import com.qualitygate.domain.repo.IngestTokenRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * {@code Authorization: Bearer qg_<prefix>_<secret>} を検証する。
 *
 * <p>prefix でレコードを 1 件に絞ってから、ハッシュを定数時間で比較する。
 * ハッシュで検索すると全件走査になるうえ、比較時間から情報が漏れうるためである。
 */
public class IngestTokenAuthenticationFilter extends OncePerRequestFilter {

    public static final String TOKEN_PREFIX_MARKER = "qg_";
    private static final String BEARER = "Bearer ";

    private final IngestTokenRepository tokens;

    public IngestTokenAuthenticationFilter(IngestTokenRepository tokens) {
        this.tokens = tokens;
    }

    @Override
    @Transactional
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        authenticate(request.getHeader(HttpHeaders.AUTHORIZATION))
                .ifPresent(auth -> SecurityContextHolder.getContext().setAuthentication(auth));
        chain.doFilter(request, response);
    }

    Optional<IngestAuthentication> authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER)) {
            return Optional.empty();
        }
        String presented = authorizationHeader.substring(BEARER.length()).trim();
        return extractPrefix(presented)
                .flatMap(tokens::findByTokenPrefix)
                .filter(token -> !token.isRevoked())
                .filter(token -> matches(token, presented))
                .map(token -> {
                    token.markUsed(Instant.now());
                    return new IngestAuthentication(token.getRepositoryId(), token.getId());
                });
    }

    /** {@code qg_<prefix>_<secret>} から prefix 部分を取り出す。 */
    static Optional<String> extractPrefix(String token) {
        if (!token.startsWith(TOKEN_PREFIX_MARKER)) {
            return Optional.empty();
        }
        int separator = token.indexOf('_', TOKEN_PREFIX_MARKER.length());
        if (separator < 0) {
            return Optional.empty();
        }
        String prefix = token.substring(TOKEN_PREFIX_MARKER.length(), separator);
        return prefix.isEmpty() ? Optional.empty() : Optional.of(prefix);
    }

    private static boolean matches(IngestToken token, String presented) {
        return MessageDigest.isEqual(
                sha256(presented).getBytes(StandardCharsets.US_ASCII),
                token.getTokenHash().getBytes(StandardCharsets.US_ASCII));
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 が利用できません", e);
        }
    }
}
