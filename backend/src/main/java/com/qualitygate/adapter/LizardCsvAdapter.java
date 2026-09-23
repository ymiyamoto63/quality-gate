package com.qualitygate.adapter;

import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawFinding;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * lizard の CSV（{@code lizard --csv}）から M-07（循環的複雑度）を読む。
 *
 * <p>lizard は関数ごとに 1 行を出す。列は次のとおり（見出し行は無い。{@code NLOC} で始まる見出し行があれば読み飛ばす）。
 * <pre>
 * NLOC, CCN, token, PARAM, length, location, file, function, long_name, start, end
 * 8,2,49,1,8,"main@3-10@./hello.c","./hello.c","main","main( )",3,10
 * </pre>
 * PMD と同じく全関数の CC 値を返し、判定は quality-gate 側で行う。
 * 関数の同定子は引数まで含む {@code long_name}（オーバーロードを区別するため）。
 */
@Component
public class LizardCsvAdapter implements ArtifactAdapter {

    private static final int CCN = 1;
    private static final int FILE = 6;
    private static final int FUNCTION = 7;
    private static final int LONG_NAME = 8;
    private static final int START = 9;
    private static final int COLUMNS = 11;

    @Override
    public boolean supports(ArtifactType type) {
        return type == ArtifactType.LIZARD_CSV;
    }

    @Override
    public NormalizedReport parse(InputStream in, ParseContext context) {
        List<RawFinding> findings = new ArrayList<>();
        int rows = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                if (line.isBlank() || line.startsWith("NLOC")) {
                    continue;
                }
                List<String> columns = split(line, number);
                if (columns.size() < COLUMNS) {
                    throw new ArtifactFormatException(
                            "lizard の CSV ではありません（%d 行目の列が %d 個です。lizard --csv の出力は %d 列）"
                                    .formatted(number, columns.size(), COLUMNS));
                }
                rows++;
                RawFinding finding = toFinding(columns, number, context);
                if (finding != null) {
                    findings.add(finding);
                }
            }
        } catch (IOException e) {
            throw new ArtifactFormatException("lizard の CSV の読み取りに失敗しました: " + e.getMessage(), e);
        }
        if (rows == 0) {
            throw new ArtifactFormatException(
                    "lizard の CSV に関数がありません。解析の対象が空か、lizard --csv 以外の出力が送信されています");
        }
        return NormalizedReport.of(ArtifactType.LIZARD_CSV, List.of(), findings);
    }

    private static RawFinding toFinding(List<String> columns, int number, ParseContext context) {
        String filePath = columns.get(FILE);
        String modulePath = PmdXmlAdapter.relativize(stripDotSlash(filePath));
        if (context.isExcluded(modulePath)) {
            return null;
        }
        String repoPath = PmdXmlAdapter.repoRelative(filePath, modulePath, context.componentName());
        int complexity = integer(columns.get(CCN), "CCN", number);
        String member = columns.get(LONG_NAME).isBlank() ? columns.get(FUNCTION) : columns.get(LONG_NAME);
        Integer start = columns.get(START).isBlank() ? null : integer(columns.get(START), "start", number);

        return new RawFinding("M-07", "CyclomaticComplexity", Severity.INFO,
                "%s の循環的複雑度は %d です".formatted(member, complexity),
                repoPath, start, context.componentName(), modulePath + "#" + member,
                Map.of("complexity", complexity,
                        "member", member,
                        "scope", context.scope() == null ? "head" : context.scope()));
    }

    private static String stripDotSlash(String path) {
        return path.startsWith("./") ? path.substring(2) : path;
    }

    private static int integer(String raw, String column, int number) {
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            throw new ArtifactFormatException(
                    "%d 行目の %s が数値ではありません: %s".formatted(number, column, raw), e);
        }
    }

    /** CSV の 1 行を列に分ける。二重引用符で囲まれた列（中のカンマと "" の重ね）を扱う。 */
    static List<String> split(String line, int number) {
        List<String> columns = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                columns.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (quoted) {
            throw new ArtifactFormatException("%d 行目の引用符が閉じていません".formatted(number));
        }
        columns.add(current.toString());
        return columns;
    }
}
