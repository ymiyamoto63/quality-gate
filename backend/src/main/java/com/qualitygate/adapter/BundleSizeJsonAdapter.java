package com.qualitygate.adapter;

import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawMeasurement;
import com.qualitygate.domain.report.ReferenceValues;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ビルドした画面のファイルサイズ（{@code bundle-size-json}）から M-17（バンドルサイズ）を読む。
 *
 * <p>値は JavaScript と CSS の gzip 後の合計（KB）。ブラウザが実際に転送する量に近いため。
 * フォントや画像は内訳にだけ残す（コードの増加と、素材の追加を混ぜないため）。
 */
@Component
public class BundleSizeJsonAdapter implements ArtifactAdapter {

    private static final int LARGEST = 5;

    private final ObjectMapper objectMapper;

    public BundleSizeJsonAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ArtifactType type) {
        return type == ArtifactType.BUNDLE_SIZE_JSON;
    }

    @Override
    public NormalizedReport parse(InputStream in, ParseContext context) {
        JsonNode files = read(in).path("files");
        if (!files.isArray()) {
            throw new ArtifactFormatException(
                    "バンドルサイズの JSON ではありません（files 配列がありません）。"
                            + " {\"files\": [{\"path\", \"bytes\", \"gzipBytes\"}]} の形で送信してください");
        }
        long jsBytes = 0;
        long jsGzip = 0;
        long cssBytes = 0;
        long cssGzip = 0;
        long otherBytes = 0;
        List<Map<String, Object>> code = new ArrayList<>();
        for (JsonNode file : files) {
            String path = file.path("path").asString("");
            if (!file.path("bytes").isNumber() || !file.path("gzipBytes").isNumber() || path.isEmpty()) {
                throw new ArtifactFormatException(
                        "バンドルサイズの JSON の files の要素には path / bytes / gzipBytes が必要です: " + file);
            }
            if (context.isExcluded(path)) {
                continue;
            }
            long bytes = file.path("bytes").asLong();
            long gzip = file.path("gzipBytes").asLong();
            String name = path.toLowerCase(Locale.ROOT);
            if (name.endsWith(".js") || name.endsWith(".mjs")) {
                jsBytes += bytes;
                jsGzip += gzip;
                code.add(Map.of("path", path, "gzipBytes", gzip));
            } else if (name.endsWith(".css")) {
                cssBytes += bytes;
                cssGzip += gzip;
                code.add(Map.of("path", path, "gzipBytes", gzip));
            } else {
                otherBytes += bytes;
            }
        }
        code.sort(Comparator.comparing((Map<String, Object> f) -> (Long) f.get("gzipBytes")).reversed());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("jsBytes", jsBytes);
        detail.put("jsGzipBytes", jsGzip);
        detail.put("cssBytes", cssBytes);
        detail.put("cssGzipBytes", cssGzip);
        detail.put("otherBytes", otherBytes);
        detail.put("largest", code.subList(0, Math.min(LARGEST, code.size())));
        return NormalizedReport.of(ArtifactType.BUNDLE_SIZE_JSON, List.of(RawMeasurement.of("M-17",
                context.componentName(), ReferenceValues.kilobytes(jsGzip + cssGzip), "KB", detail)), List.of());
    }

    private JsonNode read(InputStream in) {
        try {
            return objectMapper.readTree(in);
        } catch (JacksonException e) {
            throw new ArtifactFormatException("バンドルサイズの JSON を解析できませんでした: " + e.getMessage(), e);
        }
    }
}
