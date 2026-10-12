package com.qualitygate.adapter;

import com.qualitygate.domain.metric.MetricCatalog;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawFinding;
import com.qualitygate.domain.report.RawMeasurement;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * M-06（循環的複雑度）のレポートを読むアダプタ（{@link PmdXmlAdapter} / {@link EslintJsonAdapter}）に共通する処理。
 *
 * <p>どちらのツールも絶対パスを出し、関数ごとの CC 値を違反として並べる。パスの寄せ方と、
 * 成果物が届いたことの印（解析した関数の件数）を揃えておかないと、言語によって fingerprint や
 * ERROR の扱いがずれる。
 */
final class ComplexityReports {

    private ComplexityReports() {
    }

    /**
     * 解析した関数の件数を、成果物が届いたことの印としてコンポーネントごとに返す。
     * 関数が 1 つも無いコンポーネントでも行が残り、成果物の届かなかったコンポーネント（ERROR）と区別できる。
     */
    static RawMeasurement analyzed(List<RawFinding> findings, ParseContext context) {
        return RawMeasurement.of(MetricCatalog.M_COMPLEXITY, context.componentName(),
                BigDecimal.valueOf(findings.size()), "count", Map.of("analyzedFunctions", findings.size()));
    }

    /**
     * PMD・ESLint は絶対パスを出す。計測ごとに作業ディレクトリが違うと
     * 同じ関数が別物と見なされるため、モジュール相対（{@code src/...} 以下）に寄せる。
     * fingerprint の安定性はこの形に依存する。
     */
    static String relativize(String filePath) {
        if (filePath == null) {
            return null;
        }
        String normalized = filePath.replace('\\', '/');
        for (String marker : List.of("/src/main/java/", "/src/test/java/", "/src/")) {
            int index = normalized.indexOf(marker);
            if (index >= 0) {
                return normalized.substring(index + 1);
            }
        }
        return normalized;
    }

    /**
     * 表示・リンク用のリポジトリ相対パスを決める。
     *
     * <p>モジュール相対パス（{@code src/main/java/...}）のままでは、モノレポで
     * GitHub のリンクが 404 になる。コンポーネント名が宣言されていて、かつ
     * ツールが出した絶対パスが実際に {@code /<component>/<モジュール相対>} で
     * 終わっている場合にだけ接頭辞を付ける。推測で付けると、逆に壊れたリンクを作る。
     *
     * <p>コンポーネント宣言のない単一モジュール構成では、モジュール相対パスが
     * そのままリポジトリ相対パスである。
     */
    static String repoRelative(String absolutePath, String modulePath, String componentName) {
        if (modulePath == null || componentName == null || componentName.isBlank()) {
            return modulePath;
        }
        String normalized = absolutePath.replace('\\', '/');
        return normalized.endsWith("/" + componentName + "/" + modulePath)
                ? componentName + "/" + modulePath
                : modulePath;
    }
}
