package com.qualitygate.normalize;

import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.report.IdentifiedFinding;
import com.qualitygate.domain.report.RawFinding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RenamedFingerprintsTest {

    @Test
    void 移動した関数の移動前のfingerprintを求める() {
        IdentifiedFinding moved = complexity("src/main/java/a/New.java#run()");

        Map<String, String> previous = RenamedFingerprints.of(List.of(moved),
                Map.of("backend/src/main/java/a/New.java", "backend/src/main/java/a/Old.java"));

        assertThat(previous).containsExactly(Map.entry(moved.fingerprint(),
                Fingerprints.of("M-07", "src/main/java/a/Old.java#run()")));
    }

    @Test
    void コンポーネントをまたいだ移動もモジュール相対に寄せる() {
        assertThat(RenamedFingerprints.previousModulePath("src/main/java/a/New.java",
                Map.of("shared/src/main/java/a/New.java", "backend/src/main/java/b/Old.java")))
                .isEqualTo("src/main/java/b/Old.java");
    }

    @Test
    void 接頭辞の無いパスはそのまま対応づける() {
        assertThat(RenamedFingerprints.previousModulePath("lib/new.ts", Map.of("lib/new.ts", "lib/old.ts")))
                .isEqualTo("lib/old.ts");
    }

    @Test
    void 末尾が一致する移動が複数あれば追跡しない() {
        assertThat(RenamedFingerprints.previousModulePath("src/main/java/a/New.java", Map.of(
                "backend/src/main/java/a/New.java", "backend/src/main/java/a/Old.java",
                "worker/src/main/java/a/New.java", "worker/src/main/java/a/Older.java")))
                .isNull();
    }

    @Test
    void 移動していない関数とM07以外の違反は含めない() {
        IdentifiedFinding untouched = complexity("src/main/java/a/Same.java#run()");
        RawFinding vulnerability = new RawFinding("M-06", "CVE-1", Severity.HIGH, "t",
                "backend/src/main/java/a/New.java", null, null, "src/main/java/a/New.java#x", Map.of());
        IdentifiedFinding other = new IdentifiedFinding(Fingerprints.of(vulnerability), vulnerability);

        assertThat(RenamedFingerprints.of(List.of(untouched, other),
                Map.of("backend/src/main/java/a/New.java", "backend/src/main/java/a/Old.java"))).isEmpty();
    }

    private static IdentifiedFinding complexity(String identity) {
        RawFinding finding = new RawFinding("M-07", "CyclomaticComplexity", Severity.MEDIUM, "t",
                null, 10, "backend", identity, Map.of("complexity", 20));
        return new IdentifiedFinding(Fingerprints.of(finding), finding);
    }
}
