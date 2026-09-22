package com.qualitygate.adapter;

import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawMeasurement;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * lcov.info から M-01（ブランチカバレッジ）を読む。Vitest のカバレッジ出力が対象。
 *
 * <p>形式は行指向で単純なため自前で解析する。
 * {@code SF:<path>} でファイルが始まり、{@code BRF:<found>} / {@code BRH:<hit>} が
 * そのファイルの分岐数と実行された分岐数を表す。
 */
@Component
public class LcovAdapter implements ArtifactAdapter {

    private static final String FILE_PREFIX = "SF:";
    private static final String BRANCHES_FOUND = "BRF:";
    private static final String BRANCHES_HIT = "BRH:";

    @Override
    public boolean supports(ArtifactType type) {
        return type == ArtifactType.LCOV;
    }

    @Override
    public NormalizedReport parse(InputStream in, ParseContext context) {
        int found = 0;
        int hit = 0;
        int excludedFiles = 0;
        int files = 0;
        boolean currentExcluded = false;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith(FILE_PREFIX)) {
                    String path = trimmed.substring(FILE_PREFIX.length());
                    currentExcluded = context.isExcluded(path);
                    files++;
                    if (currentExcluded) {
                        excludedFiles++;
                    }
                } else if (!currentExcluded && trimmed.startsWith(BRANCHES_FOUND)) {
                    found += parseCount(trimmed, BRANCHES_FOUND);
                } else if (!currentExcluded && trimmed.startsWith(BRANCHES_HIT)) {
                    hit += parseCount(trimmed, BRANCHES_HIT);
                }
            }
        } catch (IOException e) {
            throw new ArtifactFormatException("lcov の読み取りに失敗しました: " + e.getMessage(), e);
        }

        if (files == 0) {
            throw new ArtifactFormatException(
                    "lcov に SF: レコードがありません。カバレッジが 1 ファイルも計測されていないか、"
                            + " lcov.info ではないファイルが送信されています");
        }

        BigDecimal value = found == 0
                ? null
                : BigDecimal.valueOf(hit * 100L)
                        .divide(BigDecimal.valueOf(found), 4, RoundingMode.HALF_UP);

        return NormalizedReport.of(ArtifactType.LCOV,
                List.of(RawMeasurement.of("M-01", context.componentName(), value, "percent", Map.of(
                        "coveredBranches", hit,
                        "totalBranches", found,
                        "files", files,
                        "excludedFiles", excludedFiles))),
                List.of());
    }

    private static int parseCount(String line, String prefix) {
        String raw = line.substring(prefix.length()).trim();
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new ArtifactFormatException(
                    "%s の値が数値ではありません: %s".formatted(prefix, raw), e);
        }
    }
}
