package com.qualitygate.notify;

import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.NotificationRecord;
import com.qualitygate.domain.entity.NotificationSettings;
import com.qualitygate.domain.entity.RepositorySummary;
import com.qualitygate.domain.entity.Run;
import com.qualitygate.domain.entity.Waiver;
import com.qualitygate.domain.model.NotificationCondition;
import com.qualitygate.domain.model.RunStatus;
import com.qualitygate.domain.model.Verdict;
import com.qualitygate.domain.repo.MeasurementRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.NotificationRecordRepository;
import com.qualitygate.domain.repo.NotificationSettingsRepository;
import com.qualitygate.domain.repo.RepositorySummaryRepository;
import com.qualitygate.domain.repo.RunRepository;
import com.qualitygate.job.RetryableJobException;
import com.qualitygate.platform.config.QualityGateProperties;
import com.qualitygate.platform.id.Uuid7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 通知の判断と送信（FR-11 / FR-10-4 / FR-06-3）。通知はメールで送る。
 *
 * <p>送信は DB のトランザクションの外で行い、結果の記録だけを短いトランザクションにする。
 * 通知先の応答待ちの間に接続を占有しないためである。通知の失敗は判定結果を巻き戻さない。
 *
 * <p>同じ通知を二度送らないよう、送信履歴（notifications）で重複を抑止する。
 * 複数の宛先のうち一部だけが失敗して再実行された場合も、送れた宛先には再送しない。
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    static final String EVENT_VERDICT = "VERDICT";
    static final String EVENT_WAIVER_EXPIRING = "WAIVER_EXPIRING";
    static final String EVENT_MEASUREMENT_STALE = "MEASUREMENT_STALE";
    static final String EVENT_FULL_MEASUREMENT_STALE = "FULL_MEASUREMENT_STALE";
    static final String CHANNEL_EMAIL = "EMAIL";

    private final RunRepository runs;
    private final MonitoredRepositoryRepository repositories;
    private final MeasurementRepository measurements;
    private final RepositorySummaryRepository summaries;
    private final NotificationSettingsRepository settingsRepository;
    private final NotificationRecordRepository records;
    private final EmailSender email;
    private final QualityGateProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    @SuppressWarnings("java:S107")
    public NotificationService(RunRepository runs, MonitoredRepositoryRepository repositories,
                               MeasurementRepository measurements,
                               RepositorySummaryRepository summaries,
                               NotificationSettingsRepository settingsRepository,
                               NotificationRecordRepository records, EmailSender email,
                               QualityGateProperties properties, ObjectMapper objectMapper,
                               TransactionTemplate transactions) {
        this.runs = runs;
        this.repositories = repositories;
        this.measurements = measurements;
        this.summaries = summaries;
        this.settingsRepository = settingsRepository;
        this.records = records;
        this.email = email;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
    }

    /**
     * Run の判定を通知する。
     *
     * @param evaluationKey 判定 1 回を表す鍵。同じ判定の通知を二度送らないために使う
     */
    public void notifyVerdict(UUID runId, String evaluationKey) {
        Context context = transactions.execute(status -> loadVerdictContext(runId));
        if (context == null) {
            return;
        }

        List<Delivery> deliveries = new ArrayList<>();
        // 監視対象ブランチの判定だけを送る。PR やトピックブランチごとに送ると、
        // 本当に見るべき main の不合格が通知の洪水に埋もれる
        if (context.run().getPullRequestNumber() == null
                && context.run().getBranch().equals(context.repository().getDefaultBranch())
                && shouldNotify(context.settings().getCondition(), context.prior(),
                context.run().getVerdict())) {
            deliveries.addAll(emails(context.settings(), context.message()));
        }

        boolean retry = false;
        for (Delivery delivery : deliveries) {
            retry |= deliver(runId, context.repository().getId(), EVENT_VERDICT, delivery,
                    evaluationKey);
        }
        if (retry) {
            throw new RetryableJobException("一部の通知が一時的な障害で送れませんでした runId=" + runId, null);
        }
    }

    /**
     * 期限 7 日前に入った免除を、リポジトリごとにまとめて知らせる（FR-10-4）。
     * 免除ごとに 1 度だけ送る。
     */
    public void notifyWaiversExpiring(List<Waiver> expiring) {
        Map<UUID, List<Waiver>> byRepository = expiring.stream()
                .collect(Collectors.groupingBy(Waiver::getRepositoryId));
        byRepository.forEach((repositoryId, waivers) -> {
            Optional<MonitoredRepository> repository = repositories.findById(repositoryId);
            NotificationSettings settings = settingsOf(repositoryId);
            if (repository.isEmpty() || settings.getCondition() == NotificationCondition.DISABLED) {
                return;
            }
            NotificationMessage message = NotificationMessages.waiversExpiring(repository.get(),
                    waivers, properties.baseUrl() + "/waivers", Instant.now());
            String key = waivers.stream().map(w -> w.getId().toString()).sorted()
                    .collect(Collectors.joining(","));
            for (Delivery delivery : emails(settings, message)) {
                deliver(null, repositoryId, EVENT_WAIVER_EXPIRING, delivery, key);
            }
        });
    }

    /**
     * 計測途絶（48 時間）と完全計測途絶（設定の日数）を知らせる（FR-06-2 / FR-06-3）。
     * 途絶が続く間は同じ最終計測時刻を鍵にするため、1 つの途絶につき 1 度だけ送る。
     */
    public void notifyStaleness(Instant now, Map<UUID, Integer> fullIntervalDays) {
        for (MonitoredRepository repository : repositories.findByEnabledTrueOrderByOwnerAscNameAsc()) {
            Optional<RepositorySummary> summary = summaries.findById(repository.getId());
            NotificationSettings settings = settingsOf(repository.getId());
            if (summary.isEmpty() || settings.getCondition() == NotificationCondition.DISABLED) {
                continue;
            }
            String url = properties.baseUrl() + "/repositories/" + repository.getId();
            Instant last = summary.get().getLatestMeasuredAt();
            if (last != null && last.isBefore(now.minus(Duration.ofHours(48)))) {
                NotificationMessage message = NotificationMessages.stale(repository,
                        "計測が 48 時間以上届いていません", last, url);
                emails(settings, message).forEach(delivery ->
                        deliver(null, repository.getId(), EVENT_MEASUREMENT_STALE, delivery,
                                repository.getId() + ":" + last));
            }
            Instant lastFull = summary.get().getLastFullMeasuredAt();
            int days = fullIntervalDays.getOrDefault(repository.getId(), 7);
            if (lastFull != null && lastFull.isBefore(now.minus(Duration.ofDays(days)))) {
                NotificationMessage message = NotificationMessages.stale(repository,
                        "完全計測が %d 日以上行われていません".formatted(days), lastFull, url);
                emails(settings, message).forEach(delivery ->
                        deliver(null, repository.getId(), EVENT_FULL_MEASUREMENT_STALE, delivery,
                                repository.getId() + ":" + lastFull));
            }
        }
    }

    /**
     * 通知するか。遷移時のみ（既定）は「合格（または初回）→ 不合格」だけを送る。
     * 不合格が続く間は送らない。毎回送ると通知が読まれなくなる。
     */
    static boolean shouldNotify(NotificationCondition condition, Verdict prior, Verdict current) {
        return switch (condition) {
            case EVERY_RUN -> true;
            case FAIL_ONLY -> current == Verdict.FAIL;
            case TRANSITION -> current == Verdict.FAIL && prior != Verdict.FAIL;
            case DISABLED -> false;
        };
    }

    private Context loadVerdictContext(UUID runId) {
        Optional<Run> found = runs.findById(runId);
        if (found.isEmpty() || found.get().getStatus() != RunStatus.EVALUATED) {
            return null;
        }
        Run run = found.get();
        MonitoredRepository repository = repositories.findById(run.getRepositoryId()).orElseThrow();
        NotificationSettings settings = settingsOf(repository.getId());

        // 再評価なら直前の判定、初回の判定なら比較対象 Run の判定が「前回」
        boolean reevaluated = run.getPreviousVerdict() != null;
        Verdict prior = reevaluated ? run.getPreviousVerdict()
                : Optional.ofNullable(run.getBaselineRunId()).flatMap(runs::findById)
                        .map(Run::getVerdict).orElse(null);
        NotificationMessage message = NotificationMessages.verdict(repository, run, prior,
                reevaluated && prior != run.getVerdict(), measurements.findByRunId(runId),
                properties.baseUrl() + "/runs/" + runId);
        return new Context(run, repository, settings, prior, message);
    }

    private List<Delivery> emails(NotificationSettings settings, NotificationMessage message) {
        return recipientsOf(settings).stream()
                .map(address -> new Delivery(CHANNEL_EMAIL, address,
                        () -> email.send(address, message)))
                .toList();
    }

    /**
     * 1 件を送り、結果を記録する。送信済みなら送らない。
     *
     * @return 一時的な障害で送れず、再実行すべきか
     */
    private boolean deliver(UUID runId, UUID repositoryId, String event, Delivery delivery,
                            String dedupKey) {
        NotificationRecord record = transactions.execute(status ->
                findRecord(runId, repositoryId, event, delivery, dedupKey));
        if (record != null && NotificationRecord.SENT.equals(record.getStatus())
                && dedupKey.equals(record.getDedupKey())) {
            return false;
        }

        ChannelResult result = delivery.send().get();
        if (!result.sent()) {
            log.warn("通知を送れませんでした channel={} event={} target={} reason={}",
                    delivery.channel(), event, delivery.target(), result.error());
        }
        UUID recordId = record.getId();
        transactions.executeWithoutResult(status -> records.findById(recordId).ifPresent(r ->
                r.record(result.sent() ? NotificationRecord.SENT : NotificationRecord.FAILED,
                        null, result.error(), dedupKey)));
        return !result.sent() && result.retryable();
    }

    private NotificationRecord findRecord(UUID runId, UUID repositoryId, String event,
                                          Delivery delivery, String dedupKey) {
        Optional<NotificationRecord> existing = runId != null
                ? records.findByRunIdAndEventAndChannelAndTarget(runId, event, delivery.channel(),
                delivery.target())
                : records.findByEventAndChannelAndTargetAndDedupKey(event, delivery.channel(),
                delivery.target(), dedupKey);
        return existing.orElseGet(() -> records.save(new NotificationRecord(Uuid7.generate(),
                runId, repositoryId, event, delivery.channel(), delivery.target(),
                // 未送信の行は重複抑止の鍵を持たせない。送れた時点で鍵を付ける
                runId == null ? dedupKey : null)));
    }

    private NotificationSettings settingsOf(UUID repositoryId) {
        return settingsRepository.findById(repositoryId)
                .orElseGet(() -> new NotificationSettings(repositoryId));
    }

    private List<String> recipientsOf(NotificationSettings settings) {
        return objectMapper.readValue(settings.getEmailRecipients(), STRING_LIST);
    }

    private record Context(Run run, MonitoredRepository repository, NotificationSettings settings,
                           Verdict prior, NotificationMessage message) {
    }

    private record Delivery(String channel, String target, java.util.function.Supplier<ChannelResult> send) {
    }
}
