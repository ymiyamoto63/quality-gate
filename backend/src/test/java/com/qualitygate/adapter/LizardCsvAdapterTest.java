package com.qualitygate.adapter;

import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawFinding;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LizardCsvAdapterTest {

    private final LizardCsvAdapter adapter = new LizardCsvAdapter();

    private static final String CSV = """
            NLOC,CCN,token,PARAM,length,location,file,function,long_name,start,end
            8,2,49,1,8,"main@3-10@./src/hello.c","./src/hello.c","main","main( int argc , char ** argv )",3,10
            30,18,210,2,35,"parse@12-46@./src/parser.c","./src/parser.c","parse","parse( const char * s , int n )",12,46
            4,1,20,0,4,"gen@1-4@./src/gen/out.c","./src/gen/out.c","gen","gen( )",1,4
            """;

    @Test
    void 関数ごとのCCNを読む() {
        NormalizedReport report = adapter.parse(stream(CSV), context(List.of()));

        assertThat(report.findings()).hasSize(3);
        RawFinding parse = report.findings().get(1);
        assertThat(parse.metricId()).isEqualTo("M-07");
        assertThat(parse.detail()).containsEntry("complexity", 18)
                .containsEntry("member", "parse( const char * s , int n )");
        assertThat(parse.filePath()).isEqualTo("src/parser.c");
        assertThat(parse.line()).isEqualTo(12);
        assertThat(parse.identity()).isEqualTo("src/parser.c#parse( const char * s , int n )");
    }

    @Test
    void 見出し行が無くても読み除外を適用する() {
        String withoutHeader = CSV.lines().skip(1).reduce("", (a, b) -> a + b + "\n");
        NormalizedReport report = adapter.parse(stream(withoutHeader), context(List.of("src/gen/**")));

        assertThat(report.findings()).extracting(RawFinding::identity)
                .containsExactly("src/hello.c#main( int argc , char ** argv )",
                        "src/parser.c#parse( const char * s , int n )");
    }

    @Test
    void 引用符の中のカンマと二重引用符を扱う() {
        assertThat(LizardCsvAdapter.split("1,\"a,b\",\"say \"\"hi\"\"\",x", 1))
                .containsExactly("1", "a,b", "say \"hi\"", "x");
    }

    @Test
    void 形式が違えば理由つきで拒否する() {
        assertThatThrownBy(() -> adapter.parse(stream("a,b,c\n"), context(List.of())))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("列");
        assertThatThrownBy(() -> adapter.parse(stream("\n"), context(List.of())))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("関数がありません");
        assertThatThrownBy(() -> adapter.parse(stream("8,x,49,1,8,l,f,fn,fn(),3,10\n"), context(List.of())))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("CCN");
    }

    private static ParseContext context(List<String> exclusions) {
        return new ParseContext(null, "head", exclusions);
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
