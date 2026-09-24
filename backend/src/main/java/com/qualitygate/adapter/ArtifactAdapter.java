package com.qualitygate.adapter;

import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;

import java.io.InputStream;

/**
 * ツール固有フォーマットを正規化モデルへ変換する。
 *
 * <p>アダプタは判定を知らない。ツールの出力を読むだけの責務に留めることで、
 * 判定基準が変わってもアダプタは変わらない（この規則は ModuleDependencyTest が検証する）。
 */
public interface ArtifactAdapter {

    boolean supports(ArtifactType type);

    /** ストリームは呼び出し側が閉じる。形式不正は {@link ArtifactFormatException} を投げる。 */
    NormalizedReport parse(InputStream in, ParseContext context);

    /**
     * 成果物の型を添えて読む。1 つのアダプタが複数の型を受け持ち、型によって供給する指標が
     * 変わる場合（JUnit XML の契約テストとすべてのテスト）に上書きする。
     */
    default NormalizedReport parse(InputStream in, ParseContext context, ArtifactType type) {
        return parse(in, context);
    }
}
