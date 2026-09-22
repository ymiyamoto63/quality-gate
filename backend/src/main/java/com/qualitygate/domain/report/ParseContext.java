package com.qualitygate.domain.report;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.List;

/**
 * パースに必要な周辺情報。
 *
 * <p>アダプタは DB にもしきい値にもアクセスしない。入力を読んで構造化するだけの責務に閉じる。
 *
 * @param componentName アップロード時に宣言されたコンポーネント名
 * @param scope         base / head（M-07 のベース比較用）
 * @param exclusions    計測除外の glob パターン
 */
public record ParseContext(String componentName, String scope, List<String> exclusions) {

    public static final String SCOPE_BASE = "base";

    public boolean isBaseScope() {
        return SCOPE_BASE.equals(scope);
    }

    /**
     * 計測除外に一致するか。
     *
     * <p>除外はすべての指標で共通に適用する（docs/02-metrics-spec.md 0.2）。
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
