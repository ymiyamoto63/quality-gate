package com.qualitygate.platform.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 廃止した環境変数が残っていれば起動しない。
 *
 * <p>黙って無視すると、運用者の気づかないうちに判定が変わる。たとえば
 * {@code QG_SKIPPED_TESTS_INCREASE_MAX=3} で「前回から 3 件までは増えてよい」としていた環境は、
 * 無視されると M-10 が件数の上限 0 件で判定され、それまで合格していたコミットが不合格になる。
 * 設定を書き直してもらうために、起動を止めて移行先を示す。
 */
@Component
public class RemovedSettings {

    /** 廃止した環境変数と、代わりに何をすればよいか。 */
    static final Map<String, String> REMOVED = removed();

    public RemovedSettings(Environment environment) {
        check(environment);
    }

    static void check(Environment environment) {
        REMOVED.forEach((name, instead) -> {
            String value = environment.getProperty(name);
            if (value != null && !value.isBlank()) {
                throw new IllegalStateException("%s は廃止しました。%s（設定値: %s）"
                        .formatted(name, instead, value.strip()));
            }
        });
    }

    private static Map<String, String> removed() {
        Map<String, String> removed = new LinkedHashMap<>();
        removed.put("QG_SKIPPED_TESTS_INCREASE_MAX",
                "M-10 は前回からの増加ではなく件数そのもので判定します。"
                        + "件数の上限を QG_SKIPPED_TESTS_MAX に書いてください"
                        + "（既存のスキップを認めるなら今の件数。既定 0）");
        return Map.copyOf(removed);
    }
}
