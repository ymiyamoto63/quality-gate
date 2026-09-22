package com.qualitygate.adapter;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;

/**
 * 外部から与えられた XML を安全に読む。
 *
 * <p>成果物は CI から送られてくる信頼できない入力である。DTD と外部実体参照を無効化し、
 * XXE（外部実体参照によるファイル読み出し・SSRF）とエンティティ展開攻撃を防ぐ。
 *
 * <p>StAX を使うのはストリーミング処理のため。ファイル全体をメモリに展開しない。
 */
public final class SafeXml {

    private SafeXml() {
    }

    public static XMLStreamReader reader(InputStream in) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        try {
            return factory.createXMLStreamReader(in);
        } catch (XMLStreamException e) {
            throw new ArtifactFormatException("XML として読み込めませんでした: " + e.getMessage(), e);
        }
    }
}
