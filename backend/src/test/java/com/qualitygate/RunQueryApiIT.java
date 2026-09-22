package com.qualitygate;

import com.qualitygate.config.GateConfigService;
import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.entity.RunSkippedMetric;
import com.qualitygate.domain.entity.UserAccount;
import com.qualitygate.domain.model.ArtifactType;
import com.qualitygate.domain.model.FindingState;
import com.qualitygate.domain.model.MeasurementStatus;
import com.qualitygate.domain.model.RunnerType;
import com.qualitygate.domain.model.Severity;
import com.qualitygate.domain.model.UserRole;
import com.qualitygate.domain.model.UserStatus;
import com.qualitygate.domain.model.Verdict;
import com.qualitygate.domain.report.NormalizedInput;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.domain.repo.FindingCriteria;
import com.qualitygate.domain.repo.FindingRepository;
import com.qualitygate.domain.repo.GateConfigRepository;
import com.qualitygate.domain.repo.IngestTokenRepository;
import com.qualitygate.domain.repo.JobRepository;
import com.qualitygate.domain.repo.MeasurementRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RepositorySummaryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.domain.repo.RunSkippedMetricRepository;
import com.qualitygate.domain.repo.UserAccountRepository;
import com.qualitygate.evaluate.GateThresholds;
import com.qualitygate.evaluate.RunEvaluationService;
import com.qualitygate.normalize.ReportNormalizer;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.id.Uuid7;
import com.qualitygate.platform.storage.ArtifactStore;
import com.qualitygate.platform.storage.StoredArtifact;
import com.qualitygate.platform.web.PageCursor;
import com.qualitygate.query.RunQueryService;
import com.qualitygate.query.dto.FindingListResponse;
import com.qualitygate.query.dto.RunDetailResponse;
import com.qualitygate.query.dto.RunListResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Run 詳細（S-03）と違反一覧（S-04）の参照 API を検証する。
 *
 * <p>判定済みのデータを本物のパイプラインで作ってから読む。読み取り専用の
 * 作り置きデータを流し込むと、判定側が書いた形と参照側が読む形の食い違いを
 * 見逃す。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AbstractIntegrationTest
class RunQueryApiIT {

    private static final String JACOCO = """
            <?xml version="1.0" encoding="UTF-8"?>
            <report name="quality-gate">
              <package name="com/qualitygate">
                <class name="com/qualitygate/Good" sourcefilename="Good.java">
                  <counter type="BRANCH" missed="2" covered="18"/>
                </class>
              </package>
            </report>
            """;

    /** 重大 1 件・高 1 件。深刻度による並び替えを確かめるため 2 段階を含める。 */
    private static final String TRIVY = """
            { "version": "2.1.0", "runs": [{
              "tool": { "driver": { "name": "Trivy", "rules": [
                { "id": "CVE-2026-1111", "properties": { "security-severity": "9.4" } },
                { "id": "CVE-2026-1234", "properties": { "security-severity": "8.1" } } ]}},
              "results": [
                { "ruleId": "CVE-2026-1234", "level": "error",
                  "message": { "text": "example-lib の任意コード実行" },
                  "properties": { "package": "com.example:example-lib" },
                  "locations": [{ "physicalLocation": {
                    "artifactLocation": { "uri": "backend/pom.xml" } }}] },
                { "ruleId": "CVE-2026-1111", "level": "error",
                  "message": { "text": "critical-lib の権限昇格" },
                  "properties": { "package": "com.example:critical-lib" },
                  "locations": [{ "physicalLocation": {
                    "artifactLocation": { "uri": "backend/pom.xml" } }}] }]
            }]}
            """;

    private static final String PMD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <pmd version="7.0.0">
              <file name="/build/backend/src/main/java/com/qualitygate/Good.java">
                <violation beginline="42" rule="CyclomaticComplexity" method="big">
            The method 'big()' has a cyclomatic complexity of 20.
                </violation>
              </file>
            </pmd>
            """;

    @Value("${local.server.port}")
    int port;

    @Autowired UserAccountRepository users;
    @Autowired MonitoredRepositoryRepository repositories;
    @Autowired IngestTokenRepository tokens;
    @Autowired RunRepository runs;
    @Autowired RunSkippedMetricRepository skippedMetrics;
    @Autowired ArtifactRecordRepository artifacts;
    @Autowired MeasurementRepository measurements;
    @Autowired FindingRepository findings;
    @Autowired RepositorySummaryRepository summaries;
    @Autowired JobRepository jobs;
    @Autowired GateConfigRepository gateConfigs;
    @Autowired ArtifactStore artifactStore;
    @Autowired ReportNormalizer normalizer;
    @Autowired RunEvaluationService evaluationService;
    @Autowired GateConfigService gateConfigService;
    @Autowired RunQueryService queryService;
    @Autowired WebApplicationContext webApplicationContext;

    private UUID repositoryId;

    @BeforeEach
    void setUp() {
        jobs.deleteAll();
        findings.deleteAll();
        measurements.deleteAll();
        artifacts.deleteAll();
        skippedMetrics.deleteAll();
        summaries.deleteAll();
        runs.deleteAll();
        gateConfigs.deleteAll();
        tokens.deleteAll();
        repositories.deleteAll();
        users.deleteAll();

        UserAccount admin = users.save(new UserAccount(Uuid7.generate(), "ymiyamoto63",
                UserRole.ADMIN, UserStatus.ACTIVE, null));
        MonitoredRepository repository = repositories.save(new MonitoredRepository(
                Uuid7.generate(), "ymiyamoto63", "quality-gate", admin.getId()));
        repositoryId = repository.getId();
    }

    @Test
    void Run詳細は指標をカテゴリごとに返す() {
        Run run = evaluated(Instant.parse("2026-09-22T00:00:00Z"));

        RunDetailResponse detail = queryService.detail(run.getId());

        assertThat(detail.verdict()).isEqualTo(Verdict.FAIL);
        assertThat(detail.repository().fullName()).isEqualTo("ymiyamoto63/quality-gate");
        // URL の組み立てはサーバが持つ
        assertThat(detail.commitUrl())
                .isEqualTo("https://github.com/ymiyamoto63/quality-gate/commit/"
                        + run.getCommitSha());

        // カテゴリは要件定義の指標表と同じ並び
        assertThat(detail.categories()).extracting(RunDetailResponse.RunCategory::category)
                .containsExactly("機能テスト", "セキュリティ", "コード構造");

        RunDetailResponse.RunCategory security = detail.categories().stream()
                .filter(c -> c.category().equals("セキュリティ")).findFirst().orElseThrow();
        assertThat(security.status()).isEqualTo(MeasurementStatus.FAIL);
        // 不合格を含むカテゴリは開いた瞬間に見えている状態にする
        assertThat(security.expandByDefault()).isTrue();
        assertThat(security.metrics()).singleElement().satisfies(metric -> {
            assertThat(metric.metricId()).isEqualTo("M-06");
            assertThat(metric.name()).isEqualTo("重大・高 脆弱性件数");
            assertThat(metric.threshold()).containsEntry("operator", "<=");
            // 違反件数を添えて「違反 2 件を見る」への導線にする
            assertThat(metric.findingCount()).isEqualTo(2);
        });

        RunDetailResponse.RunCategory functional = detail.categories().getFirst();
        assertThat(functional.status()).isEqualTo(MeasurementStatus.PASS);
        // 合格だけのカテゴリは初期状態で折りたたむ
        assertThat(functional.expandByDefault()).isFalse();

        assertThat(detail.findingSummary().initial()).isEqualTo(3);
        assertThat(detail.findingSummary().newCount()).isZero();
        assertThat(detail.artifactCount()).isEqualTo(3);
    }

    /**
     * 合格ラインの形をすべての指標で揃える。
     *
     * <p>画面は {@code operator} と {@code value} だけで「≥ 75%」「≤ 0 件」と描く。
     * 指標ごとに形が違うと、画面に指標ごとの分岐が生まれる。実際 M-06 は
     * {@code maxCritical} / {@code maxHigh} しか持たず、合格ラインが表示されなかった。
     */
    @Test
    void 合格ラインはどの指標でも同じ形で返る() {
        Run run = evaluated(Instant.parse("2026-09-22T00:00:00Z"));

        List<RunDetailResponse.RunMetric> metrics = queryService.detail(run.getId())
                .categories().stream().flatMap(c -> c.metrics().stream()).toList();

        assertThat(metrics).isNotEmpty().allSatisfy(metric ->
                assertThat(metric.threshold())
                        .as("%s の合格ライン", metric.metricId())
                        .containsKeys("operator", "value"));
    }

    @Test
    void 二回目のRunでは前回比と比較対象が返る() {
        evaluated(Instant.parse("2026-09-21T00:00:00Z"));
        // カバレッジが 90% から 80% に下がる
        Run second = evaluated(Instant.parse("2026-09-22T00:00:00Z"),
                JACOCO.replace("missed=\"2\" covered=\"18\"", "missed=\"4\" covered=\"16\""));

        RunDetailResponse detail = queryService.detail(second.getId());

        assertThat(detail.baselineRunId()).isNotNull();

        RunDetailResponse.RunMetric coverage = detail.categories().getFirst().metrics().getFirst();
        assertThat(coverage.previousValue()).isNotNull();
        assertThat(coverage.delta()).isNegative();
        // 向きの判断はサーバが持つ。カバレッジは下がれば悪化
        assertThat(coverage.deltaImproved()).isFalse();
    }

    @Test
    void 初回Runでは前回比を出さない() {
        Run run = evaluated(Instant.parse("2026-09-22T00:00:00Z"));

        RunDetailResponse.RunMetric coverage =
                queryService.detail(run.getId()).categories().getFirst().metrics().getFirst();

        // 前回値が無いことと「前回と同じ」は別物なので 0 を返さない
        assertThat(coverage.previousValue()).isNull();
        assertThat(coverage.delta()).isNull();
        assertThat(coverage.deltaImproved()).isNull();
    }

    @Test
    void 受理されなかったスキップ申告も詳細に残る() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", JACOCO);
        skippedMetrics.save(new RunSkippedMetric(run.getId(), "M-07", "理由なく省略"));
        evaluate(run);

        RunDetailResponse detail = queryService.detail(run.getId());

        assertThat(detail.skippedMetrics()).singleElement().satisfies(skip -> {
            assertThat(skip.metricId()).isEqualTo("M-07");
            assertThat(skip.name()).isEqualTo("循環的複雑度 15 超の新規関数数");
            // 申告の事実は残し、なぜ ERROR になったのかを説明できるようにする
            assertThat(skip.accepted()).isFalse();
        });
    }

    @Test
    void 処理失敗のRunは対処方法つきで返る() {
        Run run = createRun(Instant.parse("2026-09-22T00:00:00Z"));
        run.markFailed("CONFIG_VALIDATION_FAILED", "4 行目 metrics.branch_coverage.threshold: …");
        runs.save(run);

        RunDetailResponse detail = queryService.detail(run.getId());

        // 処理失敗（FAILED）は判定結果 FAIL ではない
        assertThat(detail.verdict()).isNull();
        assertThat(detail.failure()).isNotNull();
        assertThat(detail.failure().errorCode()).isEqualTo("CONFIG_VALIDATION_FAILED");
        assertThat(detail.failure().hint()).contains(".quality-gate.yml");
        assertThat(detail.categories()).isEmpty();
    }

    @Test
    void 違反は状態と深刻度の順に返り並びはページをまたいでも保たれる() {
        Run run = evaluated(Instant.parse("2026-09-22T00:00:00Z"));

        FindingCriteria all = new FindingCriteria(run.getId(), Set.of(), Set.of(), Set.of(), null);

        FindingListResponse page1 = queryService.findings(run.getId(), all, 2, null);
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.hasMore()).isTrue();
        assertThat(page1.totalCount()).isEqualTo(3);
        // 重大 → 高 の順。INFO（複雑度）は最後
        assertThat(page1.items()).extracting(FindingListResponse.FindingItem::severity)
                .containsExactly(Severity.CRITICAL, Severity.HIGH);

        FindingListResponse page2 =
                queryService.findings(run.getId(), all, 2, page1.nextCursor());
        assertThat(page2.items()).hasSize(1);
        assertThat(page2.hasMore()).isFalse();
        assertThat(page2.nextCursor()).isNull();
        assertThat(page2.items().getFirst().severity()).isEqualTo(Severity.INFO);

        // ページ間で重複しない
        assertThat(page1.items()).extracting(FindingListResponse.FindingItem::findingId)
                .doesNotContainAnyElementsOf(
                        page2.items().stream().map(FindingListResponse.FindingItem::findingId).toList());
    }

    @Test
    void 違反は指標と深刻度で絞り込める() {
        Run run = evaluated(Instant.parse("2026-09-22T00:00:00Z"));

        FindingListResponse critical = queryService.findings(run.getId(),
                new FindingCriteria(run.getId(), Set.of("M-06"), Set.of(),
                        Set.of(Severity.CRITICAL), null), 20, null);

        assertThat(critical.totalCount()).isEqualTo(1);
        assertThat(critical.items()).singleElement().satisfies(item -> {
            assertThat(item.metricName()).isEqualTo("重大・高 脆弱性件数");
            assertThat(item.title()).contains("critical-lib");
            // 該当箇所へのリンクはサーバが組み立てる
            assertThat(item.sourceUrl()).isEqualTo(
                    "https://github.com/ymiyamoto63/quality-gate/blob/"
                            + run.getCommitSha() + "/backend/pom.xml");
            // 免除機能は未実装のため常に null
            assertThat(item.waiver()).isNull();
        });
    }

    @Test
    void 複雑度の違反はモノレポでも辿れるパスを返す() {
        Run run = evaluated(Instant.parse("2026-09-22T00:00:00Z"));

        FindingListResponse complexity = queryService.findings(run.getId(),
                new FindingCriteria(run.getId(), Set.of("M-07"), Set.of(), Set.of(), null),
                20, null);

        // PMD の絶対パスは /build/backend/src/... なので、
        // モジュール相対の src/... のままではリンクが 404 になる
        assertThat(complexity.items()).singleElement().satisfies(item -> {
            assertThat(item.filePath())
                    .isEqualTo("backend/src/main/java/com/qualitygate/Good.java");
            assertThat(item.sourceUrl()).endsWith("/Good.java#L42");
        });
    }

    @Test
    void 状態を指定しなければ解消済みは返さない() {
        evaluated(Instant.parse("2026-09-21T00:00:00Z"));
        // 脆弱性がすべて消えた 2 回目
        Run second = evaluated(Instant.parse("2026-09-22T00:00:00Z"), JACOCO,
                """
                { "version": "2.1.0", "runs": [{
                  "tool": { "driver": { "name": "Trivy", "rules": [] }},
                  "results": []
                }]}
                """, PMD);

        FindingListResponse defaults = queryService.findings(second.getId(),
                new FindingCriteria(second.getId(), Set.of(),
                        Set.of(FindingState.NEW, FindingState.CONTINUING, FindingState.INITIAL),
                        Set.of(), null), 20, null);

        assertThat(defaults.items()).extracting(FindingListResponse.FindingItem::state)
                .containsOnly(FindingState.CONTINUING);

        // 解消は明示的に選んだときだけ出す
        FindingListResponse resolved = queryService.findings(second.getId(),
                new FindingCriteria(second.getId(), Set.of(), Set.of(FindingState.RESOLVED),
                        Set.of(), null), 20, null);
        assertThat(resolved.totalCount()).isEqualTo(2);
    }

    @Test
    void Run一覧は新しい順にカーソルで送れる() {
        evaluated(Instant.parse("2026-09-20T00:00:00Z"));
        evaluated(Instant.parse("2026-09-21T00:00:00Z"));
        evaluated(Instant.parse("2026-09-22T00:00:00Z"));

        RunListResponse page1 = queryService.list(repositoryId, 2, null);
        assertThat(page1.items()).extracting(RunListResponse.RunSummary::measuredAt)
                .containsExactly(Instant.parse("2026-09-22T00:00:00Z"),
                        Instant.parse("2026-09-21T00:00:00Z"));
        assertThat(page1.hasMore()).isTrue();

        RunListResponse page2 = queryService.list(repositoryId, 2, page1.nextCursor());
        assertThat(page2.items()).extracting(RunListResponse.RunSummary::measuredAt)
                .containsExactly(Instant.parse("2026-09-20T00:00:00Z"));
        assertThat(page2.hasMore()).isFalse();
    }

    @Test
    void 壊れたカーソルは400で返す() {
        Run run = evaluated(Instant.parse("2026-09-22T00:00:00Z"));
        FindingCriteria all = new FindingCriteria(run.getId(), Set.of(), Set.of(), Set.of(), null);

        // 原因はリクエスト側にあり、サーバの異常として警報を上げる対象ではない
        assertThatThrownBy(() -> queryService.findings(run.getId(), all, 20, "not-a-cursor"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cursor");

        // 種類の違うカーソルも拒否する（Run 一覧のカーソルを違反一覧に渡すなど）
        String keyset = PageCursor.ofKeyset(Instant.now(), UUID.randomUUID());
        assertThatThrownBy(() -> queryService.findings(run.getId(), all, 20, keyset))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void 存在しないRunは404で返す() {
        UUID unknown = Uuid7.generate();
        assertThatThrownBy(() -> queryService.detail(unknown))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining(unknown.toString());
    }

    @Test
    void 参照APIはセッション認証を要求する() {
        Run run = evaluated(Instant.parse("2026-09-22T00:00:00Z"));
        RestClient client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (req, res) -> { })
                .build();

        // Ingest 用の経路（POST と /status）に紛れ込ませない
        assertThat(client.get().uri("/api/v1/runs/{id}", run.getId())
                .retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(client.get().uri("/api/v1/runs/{id}/findings", run.getId())
                .retrieve().toBodilessEntity().getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * 実際の JSON まで通す。サービス層の戻り値だけを検証すると、直列化の段
     * （列挙の表記、数値の型、日時の形式、jsonb をオブジェクトとして返せているか）
     * が抜ける。画面が読むのはこの JSON であり、ここが契約である。
     */
    @Test
    void 応答のJSONが画面の読む形になっている() throws Exception {
        Run run = evaluated(Instant.parse("2026-09-22T02:10:00Z"));
        MockMvcTester tester = authenticatedTester();

        assertThat(tester.get().uri("/api/v1/runs/{id}", run.getId()).exchange())
                .hasStatusOk()
                .bodyJson()
                .satisfies(content -> {
                    var json = content.assertThat();
                    // 日時は UTC の RFC 3339。表示時のタイムゾーン変換は画面の責務
                    json.extractingPath("$.measuredAt").asString()
                            .isEqualTo("2026-09-22T02:10:00Z");
                    // 列挙は名前のまま。画面は表示語彙をこの値から引く
                    json.extractingPath("$.verdict").isEqualTo("FAIL");
                    json.extractingPath("$.runnerType").isEqualTo("self-hosted");
                    // 判定対象の値は文字列ではなく数値で返す
                    json.extractingPath("$.categories[0].metrics[0].value").asNumber()
                            .isEqualTo(90.0);
                    // jsonb はオブジェクトとして返す。文字列のままだと画面で再パースになる
                    json.extractingPath("$.categories[0].metrics[0].threshold.operator")
                            .isEqualTo(">=");
                    // 前回値が無ければ差分は 0 ではなく null
                    json.extractingPath("$.categories[0].metrics[0].delta").isNull();
                });

        assertThat(tester.get().uri("/api/v1/runs/{id}/findings", run.getId()).exchange())
                .hasStatusOk()
                .bodyJson()
                .satisfies(content -> {
                    var json = content.assertThat();
                    json.extractingPath("$.totalCount").asNumber().isEqualTo(3);
                    json.extractingPath("$.items[0].severity").isEqualTo("CRITICAL");
                    json.extractingPath("$.items[0].sourceUrl").asString()
                            .startsWith("https://github.com/ymiyamoto63/quality-gate/blob/");
                });

        storeFixtures(tester, run);
    }

    /**
     * 画面のアクセシビリティ検査（M-10）で使う応答例を書き出す。
     *
     * <p>手で書いた例を置くと、API が変わっても検査は通り続け、実際の画面だけが
     * 壊れる。{@code api/openapi.yml} と同じく、<strong>実物から生成する</strong>。
     */
    private static void storeFixtures(MockMvcTester tester, Run run) throws Exception {
        java.nio.file.Path directory = java.nio.file.Path.of("..", "frontend", "e2e", "fixtures");
        java.nio.file.Files.createDirectories(directory);

        java.nio.file.Files.writeString(directory.resolve("run-detail.json"),
                tester.get().uri("/api/v1/runs/{id}", run.getId())
                        .exchange().getResponse().getContentAsString(),
                java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.writeString(directory.resolve("findings.json"),
                tester.get().uri("/api/v1/runs/{id}/findings?state=NEW&state=CONTINUING"
                                + "&state=INITIAL", run.getId())
                        .exchange().getResponse().getContentAsString(),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void 綴りを間違えた絞り込みは黙って無視せず400で返す() {
        Run run = evaluated(Instant.parse("2026-09-22T02:10:00Z"));

        // 無視すると、利用者には「絞り込んだのに全件出た」ように見える
        assertThat(authenticatedTester().get()
                .uri("/api/v1/runs/{id}/findings?severity=CRITICALL", run.getId())
                .exchange())
                .hasStatus(HttpStatus.BAD_REQUEST.value());
    }

    /**
     * ログイン済みの利用者として叩く MockMvc。
     *
     * <p>GitHub OAuth のリダイレクトを踏まずにセッション認証済みの状態を作る。
     * 未認証で弾かれることは HTTP の別テストで確かめており、ここで見たいのは
     * 認証を通った先の応答の形である。
     */
    private MockMvcTester authenticatedTester() {
        return MockMvcTester.create(MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(org.springframework.security.test.web.servlet.setup
                        .SecurityMockMvcConfigurers.springSecurity())
                .defaultRequest(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get("/")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.user("viewer")))
                .build());
    }

    private Run evaluated(Instant measuredAt) {
        return evaluated(measuredAt, JACOCO, TRIVY, PMD);
    }

    private Run evaluated(Instant measuredAt, String jacoco) {
        return evaluated(measuredAt, jacoco, TRIVY, PMD);
    }

    private Run evaluated(Instant measuredAt, String jacoco, String sarif, String pmd) {
        Run run = createRun(measuredAt);
        attach(run, ArtifactType.JACOCO_XML, "jacoco.xml", "backend", jacoco);
        attach(run, ArtifactType.SARIF, "trivy.sarif", null, sarif);
        attach(run, ArtifactType.PMD_XML, "pmd.xml", "backend", pmd);
        return evaluate(run);
    }

    private Run evaluate(Run run) {
        List<ArtifactRecord> records = artifacts.findByRunId(run.getId());
        GateConfigService.Resolved config = gateConfigService.resolve(run, records);
        GateThresholds thresholds = GateThresholds.from(config.document());
        NormalizedInput input = normalizer.normalize(records, thresholds.exclusions());
        evaluationService.evaluate(run.getId(), input, thresholds,
                config.isDefault() ? null : config.gateConfig().getId());
        return runs.findById(run.getId()).orElseThrow();
    }

    private Run createRun(Instant measuredAt) {
        String commitSha = String.format("%040x", Math.abs(measuredAt.hashCode()));
        Run run = new Run(Uuid7.generate(), repositoryId, commitSha, "main",
                RunnerType.SELF_HOSTED, "github-actions", measuredAt,
                runs.findMaxAttempt(repositoryId, commitSha) + 1);
        run.finalizeIngest();
        return runs.save(run);
    }

    private void attach(Run run, ArtifactType type, String filename, String component,
                        String content) {
        StoredArtifact stored = artifactStore.store(run.getId().toString(), filename,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        artifacts.save(new ArtifactRecord(Uuid7.generate(), run.getId(), type, filename,
                stored.sizeBytes(), stored.sha256(), stored.storageKey(),
                component, null, null));
    }
}
