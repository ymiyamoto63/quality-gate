package com.qualitygate.adapter;

import com.qualitygate.domain.report.NormalizedReport;
import com.qualitygate.domain.report.ParseContext;
import com.qualitygate.domain.report.RawMeasurement;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatlingLogAdapterTest {

    private final GatlingLogAdapter adapter = new GatlingLogAdapter();

    /** 20 件のリクエスト（応答時間 10〜200ms）を 1 秒ごとに送り、最後の 1 件だけ失敗する。 */
    private static String log() {
        StringBuilder log = new StringBuilder("RUN\tqg.Simulation\tqgsimulation\t1000000\t\t3.7.6\n");
        log.append("USER\tusers\tSTART\t1000000\t1000000\n");
        for (int i = 1; i <= 20; i++) {
            long start = 1_000_000L + (i - 1) * 1000L;
            String group = i % 2 == 0 ? "dashboard" : "";
            log.append("REQUEST\t%s\treq%d\t%d\t%d\t%s\t%s\n".formatted(
                    group, i, start, start + i * 10L, i == 20 ? "KO" : "OK", i == 20 ? "status 500" : " "));
        }
        return log.toString();
    }

    @Test
    void 応答時間のp95と到達率と失敗率を求める() {
        NormalizedReport report = adapter.parse(stream(log()), context(Map.of("name", "perf-staging")));

        Map<String, RawMeasurement> byMetric = new java.util.HashMap<>();
        report.measurements().forEach(m -> byMetric.put(m.metricId(), m));
        // 20 件の 95 パーセンタイル（最近順位法）は 19 番目 = 190ms
        assertThat(byMetric.get("M-03").value()).isEqualByComparingTo("190");
        assertThat(byMetric.get("M-03").variant()).isEqualTo("perf-staging");
        // 20 件 / (最初の開始 〜 最後の終了 = 19.2 秒)
        assertThat(byMetric.get("M-03").detail()).containsEntry("requests", 20L).containsEntry("failedRequests", 1L);
        assertThat(byMetric.get("M-05").value()).isEqualByComparingTo("5.0000");
        assertThat(((Map<?, ?>) byMetric.get("M-03").detail().get("scenarios")).get("dashboard")).isNotNull();
    }

    @Test
    void ウォームアップの間に始まったリクエストを除く() {
        NormalizedReport report = adapter.parse(stream(log()),
                context(Map.of("name", "perf-staging", "warmupSeconds", 10)));

        assertThat(report.measurements().getFirst().detail()).containsEntry("requests", 10L);
    }

    @Test
    void p95は最近順位法で求める() {
        List<Long> values = new ArrayList<>();
        for (long i = 1; i <= 100; i++) {
            values.add(i);
        }
        assertThat(GatlingLogAdapter.p95(values)).isEqualByComparingTo("95");
        assertThat(GatlingLogAdapter.p95(List.of(7L))).isEqualByComparingTo("7");
    }

    @Test
    void 計測環境の名前が無ければ拒否する() {
        assertThatThrownBy(() -> adapter.parse(stream(log()), new ParseContext(null, null, List.of())))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("environment.name");
    }

    @Test
    void バイナリ形式と別のファイルは理由つきで拒否する() {
        InputStream binary = new ByteArrayInputStream(new byte[] {0x00, 0x01, 0x02, 'R', 'U', 'N'});
        assertThatThrownBy(() -> adapter.parse(binary, context(Map.of("name", "perf"))))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("バイナリ形式");
        assertThatThrownBy(() -> adapter.parse(stream("hello\n"), context(Map.of("name", "perf"))))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("simulation.log ではありません");
        assertThatThrownBy(() -> adapter.parse(stream("RUN\tx\n"), context(Map.of("name", "perf"))))
                .isInstanceOf(ArtifactFormatException.class)
                .hasMessageContaining("REQUEST の行がありません");
    }

    private static ParseContext context(Map<String, Object> environment) {
        return new ParseContext(null, null, List.of(), Map.of("environment", environment));
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
