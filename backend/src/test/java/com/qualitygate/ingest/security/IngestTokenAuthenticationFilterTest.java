package com.qualitygate.ingest.security;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IngestTokenAuthenticationFilterTest {

    @Test
    void トークンからprefixを取り出せる() {
        Optional<String> prefix = IngestTokenAuthenticationFilter
                .extractPrefix("qg_a1b2c3d4_XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX");

        assertThat(prefix).contains("a1b2c3d4");
    }

    @Test
    void 形式が違うトークンからはprefixを取り出さない() {
        assertThat(IngestTokenAuthenticationFilter.extractPrefix("Bearer something")).isEmpty();
        assertThat(IngestTokenAuthenticationFilter.extractPrefix("qg_")).isEmpty();
        assertThat(IngestTokenAuthenticationFilter.extractPrefix("qg_nounderscore")).isEmpty();
        assertThat(IngestTokenAuthenticationFilter.extractPrefix("")).isEmpty();
    }

    @Test
    void 同じ入力からは同じハッシュが得られる() {
        String token = "qg_a1b2c3d4_secret";

        assertThat(IngestTokenAuthenticationFilter.sha256(token))
                .isEqualTo(IngestTokenAuthenticationFilter.sha256(token))
                .hasSize(64)
                .isNotEqualTo(IngestTokenAuthenticationFilter.sha256(token + "x"));
    }
}
