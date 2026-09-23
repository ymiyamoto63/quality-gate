package com.qualitygate.ingest;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 取り込みのメトリクス（docs/initial/05-architecture.md 10.2）。
 *
 * <ul>
 *   <li>{@code qg.ingest.runs}（{@code result} = created / rejected / finalized）: Run の作成と確定</li>
 *   <li>{@code qg.ingest.artifacts}（{@code result} = accepted / rejected、{@code type}）: 成果物の受領</li>
 * </ul>
 * 拒否（rejected）が増えたら、CI 側の設定の誤りやトークンの失効を疑う。
 */
@Component
public class IngestMetrics {

    private final MeterRegistry registry;

    public IngestMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void run(String result) {
        registry.counter("qg.ingest.runs", "result", result).increment();
    }

    void artifact(String result, String type) {
        registry.counter("qg.ingest.artifacts", "result", result, "type", type).increment();
    }
}
