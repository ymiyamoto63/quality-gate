package com.qualitygate.domain.report;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * パースに必要な周辺情報。
 *
 * <p>アダプタは DB にもしきい値にもアクセスしない。入力を読んで構造化するだけの責務に閉じる。
 *
 * @param componentName アップロード時に宣言されたコンポーネント名
 * @param scope         base / head（M-07 のベース比較用）
 * @param exclusions    計測除外の glob パターン
 * @param metadata      アップロード時に添えられた計測メタデータ（JSON オブジェクト）
 */
public record ParseContext(String componentName, String scope, List<String> exclusions,
                           Map<String, Object> metadata) {

    public static final String SCOPE_BASE = "base";

    /**
     * M-09 の成果物のメタデータで、比較元（ベースコミット）に OpenAPI 定義が
     * 無かったことを表すキー（真偽値）。新規 API では破壊的変更を数えようがない。
     */
    public static final String BASE_SPEC_MISSING = "baseSpecMissing";

    public ParseContext(String componentName, String scope, List<String> exclusions) {
        this(componentName, scope, exclusions, Map.of());
    }

    public ParseContext {
        metadata = metadata == null ? Map.of() : metadata;
    }

    /** 文字列のメタデータ。無い・文字列でない場合は空。 */
    public Optional<String> metadataText(String key) {
        return metadata.get(key) instanceof String text && !text.isBlank()
                ? Optional.of(text)
                : Optional.empty();
    }

    /** 真偽値のメタデータ。無い・真偽値でない場合は false。 */
    public boolean metadataFlag(String key) {
        return Boolean.TRUE.equals(metadata.get(key));
    }

    public boolean isBaseScope() {
        return SCOPE_BASE.equals(scope);
    }

    /**
     * 計測除外に一致するか。
     *
     * <p>除外はすべての指標で共通に適用する（docs/initial/02-metrics-spec.md 0.2）。
     */
    public boolean isExcluded(String path) {
        if (path == null || exclusions == null || exclusions.isEmpty()) {
            return false;
        }
        String normalized = path.startsWith("./") ? path.substring(2) : path;
        return exclusions.stream().anyMatch(pattern -> matches(pattern, normalized));
    }

    private static boolean matches(String glob, String path) {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        return matcher.matches(java.nio.file.Path.of(path));
    }
}
