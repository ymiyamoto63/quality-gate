package com.qualitygate.adapter;

import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawMeasurement;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * JaCoCo XML から M-01（ブランチカバレッジ）を読む。
 *
 * <p>ルート直下の counter をそのまま使わず、<strong>クラス単位の BRANCH counter を
 * 集計する</strong>。計測除外（exclusions）に一致するクラスを差し引いてから
 * 再計算する必要があるためである。
 *
 * <p>行カバレッジではなくブランチカバレッジを採るのは、行カバレッジが
 * {@code if} 文を通過しただけで満たされ、分岐の片側しかテストしていないコードを
 * 検出できないため。
 */
@Component
public class JacocoXmlAdapter implements ArtifactAdapter {

    private static final String BRANCH = "BRANCH";
    private static final String CLASS = "class";

    @Override
    public boolean supports(ArtifactType type) {
        return type == ArtifactType.JACOCO_XML;
    }

    @Override
    public NormalizedReport parse(InputStream in, ParseContext context) {
        Counter total = new Counter();
        XMLStreamReader xml = SafeXml.reader(in);
        try {
            // counter は report / package / class / method / sourcefile のいずれの直下にも現れる。
            // 親要素を見て class 直下のものだけを数えないと、同じ分岐を何重にも数えてしまう。
            Deque<String> path = new ArrayDeque<>();
            String packageName = "";
            String sourcePath = null;
            boolean sawReport = false;

            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.END_ELEMENT) {
                    path.pollLast();
                    continue;
                }
                if (event != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                String element = xml.getLocalName();
                String parent = path.peekLast();
                switch (element) {
                    case "report" -> sawReport = true;
                    case "package" -> packageName = attr(xml, "name");
                    case "class" -> sourcePath = sourcePathOf(packageName, xml);
                    case "counter" -> {
                        if (CLASS.equals(parent)) {
                            accumulate(xml, sourcePath, context, total);
                        }
                    }
                    default -> { /* 他の要素は読み飛ばす */ }
                }
                if (!xml.isEndElement()) {
                    path.addLast(element);
                }
            }
            if (!sawReport) {
                throw new ArtifactFormatException(
                        "JaCoCo のレポートではありません（<report> 要素が見つかりません）。"
                                + " jacoco.xml を送信してください（HTML や CSV ではありません）");
            }
        } catch (XMLStreamException e) {
            throw new ArtifactFormatException(
                    "JaCoCo XML の解析に失敗しました: " + e.getMessage(), e);
        } finally {
            close(xml);
        }

        return NormalizedReport.of(ArtifactType.JACOCO_XML,
                List.of(toMeasurement(total, context.componentName())), List.of());
    }

    private void accumulate(XMLStreamReader xml, String sourcePath, ParseContext context,
                            Counter total) {
        if (sourcePath == null || !BRANCH.equals(attr(xml, "type"))) {
            return;
        }
        if (context.isExcluded(sourcePath)) {
            total.excludedFiles++;
            return;
        }
        total.covered += intAttr(xml, "covered");
        total.missed += intAttr(xml, "missed");
    }

    private static String sourcePathOf(String packageName, XMLStreamReader xml) {
        String sourceFile = attr(xml, "sourcefilename");
        if (sourceFile == null) {
            return attr(xml, "name");
        }
        return packageName == null || packageName.isEmpty()
                ? sourceFile
                : packageName + "/" + sourceFile;
    }

    /**
     * 分岐が 0 個の場合は 100% ではなく「分岐なし」として値を持たせない。
     * 100% と報告すると、分岐のないコンポーネントが合格を稼いでしまう。
     */
    private static RawMeasurement toMeasurement(Counter counter, String componentName) {
        int totalBranches = counter.covered + counter.missed;
        BigDecimal value = totalBranches == 0
                ? null
                : BigDecimal.valueOf(counter.covered * 100L)
                        .divide(BigDecimal.valueOf(totalBranches), 4, RoundingMode.HALF_UP);

        return RawMeasurement.of("M-01", componentName, value, "percent", Map.of(
                "coveredBranches", counter.covered,
                "totalBranches", totalBranches,
                "excludedFiles", counter.excludedFiles));
    }

    private static String attr(XMLStreamReader xml, String name) {
        return xml.getAttributeValue(null, name);
    }

    private static int intAttr(XMLStreamReader xml, String name) {
        String raw = attr(xml, name);
        if (raw == null) {
            return 0;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new ArtifactFormatException(
                    "counter の %s が数値ではありません: %s".formatted(name, raw), e);
        }
    }

    private static void close(XMLStreamReader xml) {
        try {
            xml.close();
        } catch (XMLStreamException e) {
            // 読み取り済みの結果には影響しない
        }
    }

    private static final class Counter {
        private int covered;
        private int missed;
        private int excludedFiles;
    }
}
