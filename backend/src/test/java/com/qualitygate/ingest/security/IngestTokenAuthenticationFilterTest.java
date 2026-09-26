package com.qualitygate.ingest.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IngestTokenAuthenticationFilterTest {

    private final IngestTokenAuthenticationFilter filter =
            new IngestTokenAuthenticationFilter(List.of("current-token", " next-token ", ""));

    @Test
    void 設定したトークンなら取り込みの権限で認証する() {
        assertThat(filter.authenticate("Bearer current-token")).hasValueSatisfying(auth -> {
            assertThat(auth.getName()).isEqualTo(IngestTokenAuthenticationFilter.PRINCIPAL);
            assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_INGEST");
        });
    }

    @Test
    void 交換中は新旧どちらのトークンも受け付ける() {
        assertThat(filter.authenticate("Bearer next-token")).isPresent();
    }

    @Test
    void 違うトークンや形式の違うヘッダは認証しない() {
        assertThat(filter.authenticate("Bearer current-tokenx")).isEmpty();
        assertThat(filter.authenticate("Bearer ")).isEmpty();
        assertThat(filter.authenticate("current-token")).isEmpty();
        assertThat(filter.authenticate("Basic current-token")).isEmpty();
        assertThat(filter.authenticate(null)).isEmpty();
    }

    @Test
    void トークンを設定していなければ何も認証しない() {
        IngestTokenAuthenticationFilter unconfigured = new IngestTokenAuthenticationFilter(List.of());

        assertThat(unconfigured.authenticate("Bearer ")).isEmpty();
        assertThat(unconfigured.authenticate("Bearer anything")).isEmpty();
    }
}
