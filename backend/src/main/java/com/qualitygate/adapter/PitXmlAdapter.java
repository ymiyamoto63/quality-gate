package com.qualitygate.adapter;

import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.model.MutationScope;
import com.qualitygate.domain.report.MutationTally;
import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawMeasurement;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PIT の {@code mutations.xml} から M-02（ミューテーションスコア）を読む。
 *
 * <p>PIT が出す「Mutation Coverage」は使わず、<strong>各 mutation の status を
 * 数え直す</strong>（docs/spec/02-metrics-spec.md M-02）。PIT の値は NO_COVERAGE の扱いが
 * 仕様の式と異なり、テストの届いていない箇所が多いほど良く見えてしまうためである。
 *
 * <p>status ごとの件数を内訳（{@code detail}）として渡す。評価器は複数の成果物を
 * 件数で合算してから計算し直すため、ここで出す値は 1 ファイル分の参考値である。
 * 式は {@link MutationTally} にだけ置く。
 *
 * <p>実行範囲（changed / all）はアップロード時のメタデータから読み、計測条件
 * （{@code variant}）として値に添える。範囲の違う値は比較できないためである。
 */
@Component
public class PitXmlAdapter implements ArtifactAdapter {

    @Override
    public boolean supports(ArtifactType type) {
        return type == ArtifactType.PIT_XML;
    }

    @Override
    public NormalizedReport parse(InputStream in, ParseContext context) {
        MutationTally tally = MutationTally.EMPTY;
        Set<String> excludedFiles = new HashSet<>();

        XMLStreamReader xml = SafeXml.reader(in);
        try {
            boolean sawRoot = false;
            Mutation current = null;
            String textTarget = null;
            StringBuilder text = new StringBuilder();

            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String element = xml.getLocalName();
                    if (!sawRoot) {
                        requireRoot(element);
                        sawRoot = true;
                    } else if ("mutation".equals(element)) {
                        current = new Mutation(xml.getAttributeValue(null, "status"));
                    } else if (current != null
                            && ("sourceFile".equals(element) || "mutatedClass".equals(element))) {
                        textTarget = element;
                        text.setLength(0);
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && textTarget != null) {
                    text.append(xml.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String element = xml.getLocalName();
                    if (current != null && element.equals(textTarget)) {
                        current.set(textTarget, text.toString().trim());
                        textTarget = null;
                    } else if ("mutation".equals(element) && current != null) {
                        String path = current.sourcePath();
                        if (context.isExcluded(path)) {
                            excludedFiles.add(path);
                        } else {
                            tally = tally.plusStatus(current.status);
                        }
                        current = null;
                    }
                }
            }
            if (!sawRoot) {
                requireRoot(null);
            }
        } catch (XMLStreamException e) {
            throw new ArtifactFormatException("PIT の XML の解析に失敗しました: " + e.getMessage(), e);
        } finally {
            close(xml);
        }

        Map<String, Object> detail = new LinkedHashMap<>(tally.toDetail());
        detail.put("excludedFiles", (long) excludedFiles.size());
        String scope = context.metadataText(MutationScope.METADATA_KEY).orElse(null);
        if (scope != null) {
            detail.put(MutationScope.METADATA_KEY, scope);
        }

        RawMeasurement measurement = RawMeasurement.of("M-02", context.componentName(),
                tally.score(), "percent", detail).withVariant(scope);
        return NormalizedReport.of(ArtifactType.PIT_XML, List.of(measurement), List.of());
    }

    private static void requireRoot(String element) {
        if (!"mutations".equals(element)) {
            throw new ArtifactFormatException(
                    "PIT のレポートではありません（<mutations> 要素が見つかりません）。"
                            + " outputFormats に XML を指定して出力した mutations.xml を送信してください"
                            + "（HTML や CSV ではありません）");
        }
    }

    private static void close(XMLStreamReader xml) {
        try {
            xml.close();
        } catch (XMLStreamException e) {
            // 読み取り済みの結果には影響しない
        }
    }

    private static final class Mutation {
        private final String status;
        private String sourceFile;
        private String mutatedClass;

        private Mutation(String status) {
            this.status = status;
        }

        private void set(String element, String value) {
            if ("sourceFile".equals(element)) {
                sourceFile = value;
            } else {
                mutatedClass = value;
            }
        }

        /**
         * 除外の照合に使うパス。JaCoCo と同じ「パッケージ相対」（com/example/Foo.java）にする。
         * 同じ exclusions を M-01 と M-02 に書けるようにするため（docs/spec/02-metrics-spec.md 0.2）。
         */
        private String sourcePath() {
            String className = mutatedClass == null ? "" : mutatedClass;
            int lastDot = className.lastIndexOf('.');
            String packagePath = lastDot < 0 ? "" : className.substring(0, lastDot).replace('.', '/');
            String file = sourceFile != null && !sourceFile.isEmpty()
                    ? sourceFile
                    : simpleName(className) + ".java";
            return packagePath.isEmpty() ? file : packagePath + "/" + file;
        }

        private static String simpleName(String className) {
            String simple = className.substring(className.lastIndexOf('.') + 1);
            int inner = simple.indexOf('$');
            return inner < 0 ? simple : simple.substring(0, inner);
        }
    }
}
