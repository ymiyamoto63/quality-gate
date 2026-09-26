package com.qualitygate.domain.metric;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 指標の説明と、合格ラインの根拠（リリース判定の画面・CSV に出す）。
 *
 * <p>読み手は開発者だけでなく、経営陣や開発に詳しくない人も想定する。専門用語は括弧で補う。
 * 文言をサーバに置くのは、画面と CSV（証跡）で同じ説明を出すため。
 *
 * <p>根拠は強さの違いを {@link Basis} で明示する。すべてを同じ口調で書くと、チームで決めただけの値まで
 * 外部の基準のように読めてしまう。根拠の文は<strong>既定値</strong>についての説明であり、
 * 合格ラインを変えた場合の理由は合格ライン（{@code *.gate.yml}）のコミットに残す。
 *
 * @param metricId   指標 ID
 * @param summary    何を見る指標か（専門用語なしの一言）
 * @param basis      根拠の種類
 * @param rationale  既定の合格ラインにした理由
 * @param risk       不合格のまま出すと何が起きるか
 * @param definition 技術的な定義（詳しく知りたい人向け）
 * @param tools      計測に使うライブラリ・ソフトウェア（収集ランナーが使うもの。対象の CI から受け付けるものも含む）
 */
public record MetricGuide(
        String metricId,
        String summary,
        Basis basis,
        String rationale,
        String risk,
        String definition,
        String tools) {

    /** 根拠の種類。 */
    public enum Basis {
        /** 公的・業界の基準がある（CVSS、WCAG、NIST など）。 */
        EXTERNAL_STANDARD("外部基準"),
        /** 広く使われている目安。絶対ではない。 */
        INDUSTRY_PRACTICE("業界の目安"),
        /** このプロジェクトで決めた値。見直しの対象。 */
        TEAM_DECISION("チーム判断");

        private final String label;

        Basis(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final List<MetricGuide> ALL = List.of(
            new MetricGuide("M-01",
                    "テストが、プログラムの分かれ道（if 文など）をどこまで通っているか",
                    Basis.INDUSTRY_PRACTICE,
                    "Google のテストのガイドでは、カバレッジ 60% を「許容」、75% を「推奨」、90% を「模範」としている。"
                            + "既定値はその「推奨」の 75%。",
                    "テストで一度も通っていない分岐の不具合は、リリース後に初めて見つかりやすい。",
                    "分岐網羅率 = テストで実行された分岐 / 全分岐 × 100。コンポーネント（backend / frontend）ごとに判定し、合算しない。",
                    "JaCoCo（Java）、Vitest の v8 カバレッジ（@vitest/coverage-v8。TypeScript）"),
            new MetricGuide("M-02",
                    "わざと埋め込んだ不具合を、テストがどれだけ見抜けるか（テストの「質」）",
                    Basis.TEAM_DECISION,
                    "業界で定まった基準はまだない。導入初期の現実的な水準として 60% とし、定着したら引き上げる。",
                    "テストの数は多くても、不具合を見逃すテストになっている恐れがある。",
                    "ミューテーションスコア = (Killed + Timeout) / (Killed + Timeout + Survived + NoCoverage) × 100。PIT で backend（Java）を計測する。",
                    "PIT（pitest。JUnit 5 用の pitest-junit5-plugin と併用）"),
            new MetricGuide("M-03",
                    "利用者 100 人のうち 95 人が、どれだけ待たずに応答を受け取れるか",
                    Basis.TEAM_DECISION,
                    "利用者が「待たされた」と感じ始める目安をもとに 500 ミリ秒とした。平均ではなく 95 パーセンタイルで見るのは、"
                            + "一部の遅い応答が平均に埋もれるため。",
                    "遅いと利用者の離脱や問い合わせが増える。",
                    "応答時間の 95 パーセンタイル（p95）。一定の負荷（到達率）をかけた状態で、全体とシナリオごとの両方を判定する。",
                    "k6（負荷試験ツール）"),
            new MetricGuide("M-04",
                    "想定した量のアクセスを、実際にさばけたか",
                    Basis.TEAM_DECISION,
                    "応答時間を公平に比べるための負荷の条件（既定は毎秒 50 リクエスト）。"
                            + "かけた負荷の 95% 未満しかさばけていなければ注意とする。",
                    "アクセスが集中したときに処理が詰まり、応答時間の結果も実際より良く見える。",
                    "実測の到達率（req/s）。負荷は結果ではなく条件として固定する（指標仕様書 M-04）。",
                    "k6（負荷試験ツール）"),
            new MetricGuide("M-05",
                    "負荷をかけたとき、処理に失敗したリクエストの割合",
                    Basis.TEAM_DECISION,
                    "1,000 回に 1 回（0.1%）を上限とした。",
                    "利用者の操作が失敗し、データの取りこぼしや問い合わせにつながる。",
                    "エラー率 = 失敗したリクエスト / 全リクエスト × 100。",
                    "k6（負荷試験ツール）"),
            new MetricGuide("M-06",
                    "使っている部品（ライブラリ）やコードに、既知のセキュリティ上の穴があるか",
                    Basis.EXTERNAL_STANDARD,
                    "深刻度が「重大」「高」のもの（国際的な評価基準 CVSS では 7.0 以上）が対象。"
                            + "攻撃の手口が公開されていることが多いため、1 件も残さない。",
                    "情報漏えいやサービス停止に直結し、事業上・法的な責任を問われうる。",
                    "SCA・SAST・コンテナイメージの走査で見つかった、深刻度 Critical / High の未解決の脆弱性の件数。",
                    "Trivy（依存ライブラリの脆弱性の走査）。対象の CI が出した Semgrep・gitleaks・OWASP Dependency-Check などの SARIF も受け付ける"),
            new MetricGuide("M-07",
                    "新しく書いたプログラムが、分岐だらけで読みにくくなっていないか",
                    Basis.EXTERNAL_STANDARD,
                    "米国 NIST の指針（SP 500-235）では 10 以下を推奨し、理由があれば 15 まで許容としている。"
                            + "既定値はその上限の 15。",
                    "修正のたびに不具合が混入しやすくなり、保守の費用が上がる。",
                    "循環的複雑度（McCabe）が 15 を超える、新規または変更した関数の数。既存の関数は数えない。",
                    "PMD（Java）、ESLint の complexity ルール（TypeScript / Vue。typescript-eslint・vue-eslint-parser）"),
            new MetricGuide("M-09",
                    "既存の利用者を壊す API の変更（互換性のない変更）が入っていないか",
                    Basis.TEAM_DECISION,
                    "互換性のない変更は、バージョンを分けて出すのが原則のため 0 件とする。",
                    "連携している画面や他システムが、リリースと同時に動かなくなる。",
                    "oasdiff で比較元と対象の OpenAPI 定義を比べ、検出された破壊的変更の件数。",
                    "oasdiff"),
            new MetricGuide("M-10",
                    "障がいのある人や高齢の人も、画面を使えるか",
                    Basis.EXTERNAL_STANDARD,
                    "国際的なガイドライン WCAG の AA 水準（既定は 2.2）で、重大（critical / serious）な違反を 0 件とする。"
                            + "日本の JIS X 8341-3 も WCAG に対応した規格。",
                    "一部の利用者が操作できなくなる。公共調達や法令への対応で求められることもある。",
                    "axe-core で自動検出した WCAG AA の違反のうち、影響度が critical / serious のものの件数。",
                    "axe-core（@axe-core/playwright）。画面は Playwright（Chromium）で開く"),
            new MetricGuide("M-11",
                    "用意したテストが、すべて通っているか",
                    Basis.TEAM_DECISION,
                    "失敗しているテストは「既知の不具合」か「壊れたテスト」のどちらかのため、すべて成功（100%）を求める。",
                    "失敗を見過ごしたまま出すと、既知の不具合をそのままリリースすることになる。",
                    "テスト成功率 = 成功 / 実行 × 100（スキップは分母に入れない）。コンポーネントごとに判定する。",
                    "JUnit（Maven Surefire / Failsafe で実行）、Vitest の junit reporter。結果は JUnit XML で受け取る"),
            new MetricGuide("M-12",
                    "実行されずに飛ばされたテストが増えていないか",
                    Basis.TEAM_DECISION,
                    "飛ばしたテストは何も確かめていないため、前回より増えることを認めない。",
                    "確かめたつもりの機能が、実は検証されていない状態でリリースされる。",
                    "スキップされたテストの件数（コンポーネントごと）。前回からの増分で判定する。",
                    "JUnit（Maven Surefire / Failsafe で実行）、Vitest の junit reporter。結果は JUnit XML で受け取る"),
            new MetricGuide("M-13",
                    "パスワードや API キーなどの秘密情報が、コードに書き込まれていないか",
                    Basis.TEAM_DECISION,
                    "コードに残った秘密情報は、履歴に残った時点で漏えいとみなすため 0 件とする。",
                    "第三者にシステムやクラウドの口座を使われ、損害や情報漏えいにつながる。",
                    "Trivy などで検出したシークレットの件数（新規に限らず、見つかったものすべて）。",
                    "Trivy（シークレットの走査）。gitleaks の SARIF も受け付ける"),
            new MetricGuide("M-14",
                    "使っている部品のライセンスに、使用を禁じたものが含まれていないか",
                    Basis.TEAM_DECISION,
                    "禁止ライセンスは、自社のソースコードの公開義務などを負いうるため 0 件とする。",
                    "契約違反や、ソースコードの公開を求められる法的なリスクがある。",
                    "禁止（forbidden）に分類されたライセンスの件数。制限（restricted）は件数の上限で判定する。",
                    "Trivy（ライセンスの走査）"));

    private static final Map<String, MetricGuide> BY_ID = ALL.stream()
            .collect(Collectors.toUnmodifiableMap(MetricGuide::metricId, Function.identity()));

    public static List<MetricGuide> all() {
        return ALL;
    }

    /**
     * 未知の指標 ID でも例外にしない（{@link MetricCatalog#of} と同じ理由）。説明が無いことを文言で示す。
     */
    public static MetricGuide of(String metricId) {
        return BY_ID.getOrDefault(metricId, new MetricGuide(metricId, "この指標の説明はありません",
                Basis.TEAM_DECISION, "—", "—", "—", "—"));
    }
}
