package com.qualitygate.release;

import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * リリース判定で指定されたタグ・コミットを、コミット SHA に解決する（UC-10）。
 *
 * <p>16 進数 7〜40 桁はコミット SHA とみなし、計測済みの Run から探す。それ以外はタグ名とみなし、
 * 収集ランナーが計測時に送ったタグ（計測したコミットを指すタグ）から探す。GitHub API は使わない（D-26）。
 * ブランチ名は受け付けない。ブランチの先頭は時間とともに動くため、リリース判定の証跡にならない。
 */
@Component
public class ReleaseRefResolver {

    private static final Pattern SHA = Pattern.compile("^[0-9a-fA-F]{7,40}$");
    /** git のタグ名に使えない文字（空白・制御文字・{@code ~^:?*[\}）と {@code ..} を拒否する。 */
    private static final Pattern INVALID_TAG = Pattern.compile("[\\s\\p{Cntrl}~^:?*\\[\\\\]|\\.\\.");
    private static final int MAX_LENGTH = 255;

    private final RunRepository runs;

    public ReleaseRefResolver(RunRepository runs) {
        this.runs = runs;
    }

    /** 指定の種類。 */
    public enum RefType {
        TAG,
        COMMIT
    }

    /**
     * @param ref       利用者が指定した文字列（前後の空白を除いたもの）
     * @param commitSha 解決したコミット SHA。計測していない短い SHA は指定のまま
     */
    public record Resolved(String ref, RefType type, String commitSha) {
    }

    public Resolved resolve(MonitoredRepository repository, String input) {
        String ref = input == null ? "" : input.strip();
        if (ref.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "タグかコミット SHA を指定してください");
        }
        if (ref.length() > MAX_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "タグかコミット SHA は %d 文字以内で指定してください".formatted(MAX_LENGTH));
        }
        if (SHA.matcher(ref).matches()) {
            return resolveCommit(repository, ref.toLowerCase(Locale.ROOT));
        }
        if (INVALID_TAG.matcher(ref).find() || ref.startsWith("/") || ref.endsWith("/")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "タグ名として使えない文字を含んでいます: " + ref);
        }
        String commitSha = runs.findLatestCommitShaByTag(repository.getId(), ref)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        ("タグ %s を付けたコミットの計測がありません。収集ランナーでタグを指定して計測するか、"
                                + "コミット SHA で指定してください").formatted(ref)));
        return new Resolved(ref, RefType.TAG, commitSha);
    }

    /** 計測していない短い SHA は完全な SHA にできないが、判定は「未計測」で変わらないため止めない。 */
    private Resolved resolveCommit(MonitoredRepository repository, String sha) {
        List<String> measured = runs.findCommitShasLike(repository.getId(), sha + "%");
        if (measured.size() > 1) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "%s は複数のコミットに一致します。もっと長く指定してください".formatted(sha));
        }
        return new Resolved(sha, RefType.COMMIT, measured.isEmpty() ? sha : measured.getFirst());
    }
}
