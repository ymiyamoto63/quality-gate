package com.qualitygate.ingest.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;

/**
 * {@code Authorization: Bearer <token>} を、設定した Ingest Token（{@code QG_INGEST_TOKEN}）と照合する。
 *
 * <p>送り手は収集ランナーだけなので、トークンはリポジトリごとに分けず 1 つにする（D-27）。
 * 交換のときに新旧を同時に受け付けられるよう、カンマ区切りで複数を設定できる。
 * 長さの違いから情報が漏れないよう、どちらも SHA-256 にしてから定数時間で比較する。
 */
public class IngestTokenAuthenticationFilter extends OncePerRequestFilter {

    static final String PRINCIPAL = "collector";
    private static final String BEARER = "Bearer ";

    private final List<byte[]> tokenHashes;

    public IngestTokenAuthenticationFilter(List<String> tokens) {
        this.tokenHashes = tokens.stream().map(String::strip).filter(t -> !t.isEmpty())
                .map(IngestTokenAuthenticationFilter::sha256).toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        authenticate(request.getHeader(HttpHeaders.AUTHORIZATION))
                .ifPresent(auth -> SecurityContextHolder.getContext().setAuthentication(auth));
        chain.doFilter(request, response);
    }

    Optional<Authentication> authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER)) {
            return Optional.empty();
        }
        byte[] presented = sha256(authorizationHeader.substring(BEARER.length()).strip());
        boolean matched = false;
        for (byte[] expected : tokenHashes) {
            // 一致しても残りと比べ続ける（何番目で一致したかを時間から推測させない）
            matched |= MessageDigest.isEqual(presented, expected);
        }
        if (!matched) {
            return Optional.empty();
        }
        return Optional.of(UsernamePasswordAuthenticationToken.authenticated(PRINCIPAL, null,
                List.of(new SimpleGrantedAuthority("ROLE_INGEST"))));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 が利用できません", e);
        }
    }
}
