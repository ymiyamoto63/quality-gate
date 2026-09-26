package com.qualitygate.release;

import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 計測済みの Run と、計測時に送られたタグからの解決を確かめる。 */
class ReleaseRefResolverTest {

    private static final String COMMIT = "a".repeat(40);

    private final RunRepository runs = mock(RunRepository.class);
    private final MonitoredRepository repository = new MonitoredRepository(UUID.randomUUID(), "o", "r", null);
    private final ReleaseRefResolver resolver = new ReleaseRefResolver(runs);

    @BeforeEach
    void setUp() {
        when(runs.findCommitShasLike(any(), any())).thenReturn(List.of());
        when(runs.findLatestCommitShaByTag(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void タグは計測時に送られたタグからコミットに解決する() {
        when(runs.findLatestCommitShaByTag(repository.getId(), "release/2026-09")).thenReturn(Optional.of(COMMIT));

        var resolved = resolver.resolve(repository, " release/2026-09 ");

        assertThat(resolved.ref()).isEqualTo("release/2026-09");
        assertThat(resolved.type()).isEqualTo(ReleaseRefResolver.RefType.TAG);
        assertThat(resolved.commitSha()).isEqualTo(COMMIT);
    }

    @Test
    void タグを付けて計測したコミットが無ければ404で計測の仕方を案内する() {
        assertThatThrownBy(() -> resolver.resolve(repository, "v9.9.9"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND))
                .hasMessageContaining("タグ v9.9.9 を付けたコミットの計測がありません")
                .hasMessageContaining("コミット SHA で指定してください");
    }

    @Test
    void 短いSHAは計測済みのRunから完全なSHAにする() {
        when(runs.findCommitShasLike(any(), eq("aaaaaaa%"))).thenReturn(List.of(COMMIT));

        var resolved = resolver.resolve(repository, "AAAAAAA");

        assertThat(resolved.type()).isEqualTo(ReleaseRefResolver.RefType.COMMIT);
        assertThat(resolved.commitSha()).isEqualTo(COMMIT);
    }

    @Test
    void 計測していない短いSHAは指定のまま返す() {
        var resolved = resolver.resolve(repository, "abcdef1");

        assertThat(resolved.type()).isEqualTo(ReleaseRefResolver.RefType.COMMIT);
        assertThat(resolved.commitSha()).isEqualTo("abcdef1");
    }

    @Test
    void 複数のコミットに一致する短いSHAは拒否する() {
        when(runs.findCommitShasLike(any(), eq("aaaaaaa%"))).thenReturn(List.of(COMMIT, "aaaaaaab" + "c".repeat(32)));

        assertThatThrownBy(() -> resolver.resolve(repository, "aaaaaaa"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("複数のコミットに一致します");
    }

    @Test
    void タグ名に使えない文字は拒否する() {
        RunRepository untouched = mock(RunRepository.class);
        ReleaseRefResolver strict = new ReleaseRefResolver(untouched);
        for (String ref : List.of("", "  ", "v1..2", "a b", "x:y", "/v1", "v1/")) {
            assertThatThrownBy(() -> strict.resolve(repository, ref))
                    .as(ref)
                    .isInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e).errorCode())
                            .isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        verifyNoInteractions(untouched);
    }
}
