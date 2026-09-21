package com.qualitygate.ingest.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

/**
 * Ingest Token による認証。
 *
 * <p>principal は「そのトークンが書き込みを許されたリポジトリ」である。
 * 利用者ではないため、参照 API では一切利用できない（書き込み専用スコープ）。
 */
public class IngestAuthentication extends AbstractAuthenticationToken {

    private final UUID repositoryId;
    private final UUID tokenId;

    public IngestAuthentication(UUID repositoryId, UUID tokenId) {
        super(List.of(new SimpleGrantedAuthority("ROLE_INGEST")));
        this.repositoryId = repositoryId;
        this.tokenId = tokenId;
        setAuthenticated(true);
    }

    public UUID repositoryId() {
        return repositoryId;
    }

    public UUID tokenId() {
        return tokenId;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return repositoryId;
    }

    @Override
    public String getName() {
        return "ingest-token:" + tokenId;
    }
}
