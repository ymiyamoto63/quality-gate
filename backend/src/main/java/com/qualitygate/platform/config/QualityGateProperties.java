package com.qualitygate.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

/**
 * quality-gate 固有の設定。既定値はここだけに持つ（application.yml は環境変数を対応づけるだけ）。
 *
 * @param repository       計測対象のリポジトリ（owner/name。{@code QG_REPOSITORY}）。quality-gate は 1 つのアプリだけを見る
 * @param artifactRoot     成果物ストアのルートディレクトリ
 * @param maxArtifactBytes 1 ファイルあたりの上限
 * @param maxRunBytes      1 Run あたりの合計上限
 * @param baseUrl          CI ログに出す自身の URL
 * @param ingestTokens     取り込み API の Ingest Token（{@code QG_INGEST_TOKEN}。カンマ区切りで複数。交換のときだけ新旧を並べる）。
 *                         空なら取り込み API はすべて 401 を返す
 * @param login            画面のログイン（共有のユーザー名とパスワード）
 * @param gate             合格ライン（環境変数で変える）
 */
@ConfigurationProperties(prefix = "quality-gate")
public record QualityGateProperties(
        String repository,
        Path artifactRoot,
        long maxArtifactBytes,
        long maxRunBytes,
        String baseUrl,
        List<String> ingestTokens,
        Login login,
        Gate gate) {

    public QualityGateProperties {
        if (repository == null || !repository.strip().matches("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")) {
            throw new IllegalArgumentException(
                    "QG_REPOSITORY に計測対象のリポジトリを owner/name の形式で指定してください（設定値: %s）"
                            .formatted(repository));
        }
        repository = repository.strip();
        if (artifactRoot == null) {
            artifactRoot = Path.of("./data/artifacts");
        }
        if (maxArtifactBytes <= 0) {
            maxArtifactBytes = 50L * 1024 * 1024;
        }
        if (maxRunBytes <= 0) {
            maxRunBytes = 200L * 1024 * 1024;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8080";
        }
        ingestTokens = ingestTokens == null ? List.of() : List.copyOf(ingestTokens);
        if (login == null) {
            login = new Login(null, null);
        }
        if (gate == null) {
            gate = Gate.defaults();
        }
    }

    /**
     * 画面のログイン。ロールは無く、ログインした人は全員同じものを見る。
     *
     * @param username 既定 {@code quality}
     * @param password 必須（{@code QG_LOGIN_PASSWORD}）。空のままでは起動しない
     */
    public record Login(String username, String password) {

        public Login {
            if (username == null || username.isBlank()) {
                username = "quality";
            }
            if (password == null || password.isBlank()) {
                throw new IllegalArgumentException("QG_LOGIN_PASSWORD に画面のログインのパスワードを指定してください");
            }
            if (password.length() < 12) {
                throw new IllegalArgumentException("QG_LOGIN_PASSWORD は 12 文字以上にしてください");
            }
        }
    }

    /**
     * 合格ライン（docs/metrics.md）。未設定（null）の項目は既定値になる。
     *
     * <p>変えた値は、その後に判定する Run から効く。判定済みの Run は、判定したときの合格ラインを
     * 指標ごとに持っている（measurements.threshold）ため、後から変えても過去の結論は変わらない。
     *
     * @param disabledMetrics         判定しない指標の ID（M-03 など）
     * @param exclusions              計測から除くファイル（glob）
     * @param branchCoverageMin       M-01 ブランチカバレッジの下限（%）
     * @param mutationScoreMin        M-02 ミューテーションスコアの下限（%）
     * @param mutationComponents      M-02 の対象コンポーネント。空なら限定しない
     * @param responseTimeP95MaxMs    M-03 応答時間 p95 の上限（ms）
     * @param arrivalRateRps          M-03 の負荷条件（到達率 req/s）。実測がこの 95% に届かなければ計測エラー
     * @param errorRateMaxPct         M-04 エラー率の上限（%）
     * @param perfScenarios           M-03 / M-04 で結果が無ければ計測エラーにするシナリオ。空なら限定しない
     * @param criticalVulnerabilitiesMax M-05 Critical の脆弱性の上限（件）
     * @param highVulnerabilitiesMax  M-05 High の脆弱性の上限（件）
     * @param complexityMax           M-06 関数の循環的複雑度の上限。超える関数が 1 つでもあれば不合格
     * @param breakingChangesMax      M-07 OpenAPI の破壊的変更の上限（件）
     * @param accessibilityViolationsMax M-08 重大なアクセシビリティ違反の上限（件）
     * @param accessibilityPages      M-08 で検査されているべき画面。空なら限定しない
     * @param testSuccessRateMin      M-09 テスト成功率の下限（%）
     * @param testCountMin            M-09 実行されたテストの最小件数
     * @param skippedTestsIncreaseMax M-10 スキップされたテストの、比較元からの増加の上限（件）
     * @param secretsMax              M-11 シークレットの上限（件）
     * @param forbiddenLicensesMax    M-12 使用禁止ライセンスの上限（件）
     */
    @SuppressWarnings("java:S107")
    public record Gate(
            List<String> disabledMetrics,
            List<String> exclusions,
            BigDecimal branchCoverageMin,
            BigDecimal mutationScoreMin,
            List<String> mutationComponents,
            BigDecimal responseTimeP95MaxMs,
            BigDecimal arrivalRateRps,
            BigDecimal errorRateMaxPct,
            List<String> perfScenarios,
            Integer criticalVulnerabilitiesMax,
            Integer highVulnerabilitiesMax,
            Integer complexityMax,
            Integer breakingChangesMax,
            Integer accessibilityViolationsMax,
            List<String> accessibilityPages,
            BigDecimal testSuccessRateMin,
            Integer testCountMin,
            Integer skippedTestsIncreaseMax,
            Integer secretsMax,
            Integer forbiddenLicensesMax) {

        public Gate {
            disabledMetrics = clean(disabledMetrics);
            // 綴りを誤った指標を黙って判定し続けないよう、知らない ID は拒否する
            for (String metricId : disabledMetrics) {
                if (!metricId.matches("^M-(0[1-9]|1[0-2])$")) {
                    throw new IllegalArgumentException(
                            "QG_DISABLED_METRICS には M-01〜M-12 の指標 ID をカンマ区切りで書いてください（設定値: %s）"
                                    .formatted(metricId));
                }
            }
            exclusions = clean(exclusions);
            branchCoverageMin = orDefault(branchCoverageMin, "75");
            mutationScoreMin = orDefault(mutationScoreMin, "60");
            mutationComponents = clean(mutationComponents);
            responseTimeP95MaxMs = orDefault(responseTimeP95MaxMs, "500");
            arrivalRateRps = orDefault(arrivalRateRps, "50");
            errorRateMaxPct = orDefault(errorRateMaxPct, "0.1");
            perfScenarios = clean(perfScenarios);
            criticalVulnerabilitiesMax = orDefault(criticalVulnerabilitiesMax, 0);
            highVulnerabilitiesMax = orDefault(highVulnerabilitiesMax, 0);
            complexityMax = orDefault(complexityMax, 15);
            breakingChangesMax = orDefault(breakingChangesMax, 0);
            accessibilityViolationsMax = orDefault(accessibilityViolationsMax, 0);
            accessibilityPages = clean(accessibilityPages);
            testSuccessRateMin = orDefault(testSuccessRateMin, "100");
            // 0 を書かれても 1 件は求める。0 件の合格は「検証していない」の言い換えにすぎない
            testCountMin = Math.max(1, orDefault(testCountMin, 1));
            skippedTestsIncreaseMax = orDefault(skippedTestsIncreaseMax, 0);
            secretsMax = orDefault(secretsMax, 0);
            forbiddenLicensesMax = orDefault(forbiddenLicensesMax, 0);
        }

        public static Gate defaults() {
            return new Gate(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null);
        }

        private static List<String> clean(List<String> values) {
            return values == null ? List.of()
                    : values.stream().map(String::strip).filter(v -> !v.isEmpty()).toList();
        }

        private static BigDecimal orDefault(BigDecimal value, String defaultValue) {
            return value == null ? new BigDecimal(defaultValue) : value;
        }

        private static int orDefault(Integer value, int defaultValue) {
            return value == null ? defaultValue : value;
        }
    }
}
