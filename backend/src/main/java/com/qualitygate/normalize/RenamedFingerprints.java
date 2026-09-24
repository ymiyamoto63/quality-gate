package com.qualitygate.normalize;

import com.qualitygate.adapter.PmdXmlAdapter;
import com.qualitygate.domain.report.IdentifiedFinding;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ファイルの移動・リネームで変わった fingerprint を、移動前の fingerprint に対応づける（指標仕様書 0.4）。
 *
 * <p>fingerprint にファイルパスを含むのは M-07（{@code モジュール相対パス#関数}）だけなので、M-07 だけを対象にする。
 * 移動した関数の中身が変わっていても、同じ関数として扱う（git が同じファイルと認めた移動のため）。
 */
final class RenamedFingerprints {

    private static final String M_COMPLEXITY = "M-07";

    private RenamedFingerprints() {
    }

    /**
     * @param renames 新しいパス → 移動前のパス（リポジトリ相対）
     * @return 今の fingerprint → 移動前の fingerprint。移動していない違反は含めない
     */
    static Map<String, String> of(List<IdentifiedFinding> headFindings, Map<String, String> renames) {
        if (renames.isEmpty()) {
            return Map.of();
        }
        Map<String, String> previous = new HashMap<>();
        for (IdentifiedFinding finding : headFindings) {
            if (!M_COMPLEXITY.equals(finding.metricId())) {
                continue;
            }
            String identity = finding.finding().identity();
            int separator = identity == null ? -1 : identity.indexOf('#');
            if (separator <= 0) {
                continue;
            }
            String modulePath = identity.substring(0, separator);
            String previousModulePath = previousModulePath(modulePath, renames);
            if (previousModulePath != null && !previousModulePath.equals(modulePath)) {
                previous.put(finding.fingerprint(), Fingerprints.of(finding.metricId(),
                        previousModulePath + identity.substring(separator)));
            }
        }
        return Map.copyOf(previous);
    }

    /**
     * モジュール相対パス（{@code src/main/java/...}）の移動前の形。リポジトリ相対の対応表から、
     * 末尾が一致する移動を 1 つだけ見つけられた場合に返す（複数あればどれか決められないため追跡しない）。
     */
    static String previousModulePath(String modulePath, Map<String, String> renames) {
        String matched = null;
        for (String current : renames.keySet()) {
            if (current.equals(modulePath) || current.endsWith("/" + modulePath)) {
                if (matched != null) {
                    return null;
                }
                matched = current;
            }
        }
        if (matched == null) {
            return null;
        }
        String before = renames.get(matched);
        // モノレポのコンポーネント（backend/ など）の接頭辞。移動の前後で同じなら、それを外せばモジュール相対になる
        String prefix = matched.substring(0, matched.length() - modulePath.length());
        if (before.startsWith(prefix)) {
            return before.substring(prefix.length());
        }
        // コンポーネントをまたいだ移動。アダプタと同じ規則でモジュール相対に寄せる
        String relative = PmdXmlAdapter.relativize("/" + before);
        return relative.startsWith("/") ? relative.substring(1) : relative;
    }
}
