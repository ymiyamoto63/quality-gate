package com.qualitygate.normalize;

import com.qualitygate.domain.report.RawFinding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 違反を Run をまたいで同一と見なすキーを生成する。
 *
 * <p>生成をアダプタではなくここに集約するのは、指標ごとの fingerprint 定義
 * （docs/spec/02-metrics-spec.md 0.4）が 1 箇所に収まっていないと、
 * アダプタごとに実装がぶれて名寄せが壊れるためである。
 *
 * <p><strong>行番号を含めない。</strong>無関係な編集で行がずれただけの違反を
 * 「新規発生」と誤判定しないため。
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static String of(RawFinding finding) {
        return of(finding.metricId(), finding.identity());
    }

    static String of(String metricId, String identity) {
        return sha256(metricId + "\u0000" + identity);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 が利用できません", e);
        }
    }
}
