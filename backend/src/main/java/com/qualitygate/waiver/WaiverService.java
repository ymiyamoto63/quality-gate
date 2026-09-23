package com.qualitygate.waiver;

import com.qualitygate.domain.entity.Finding;
import com.qualitygate.domain.entity.MonitoredRepository;
import com.qualitygate.domain.entity.RepositorySummary;
import com.qualitygate.domain.entity.Waiver;
import com.qualitygate.domain.metric.MetricCatalog;
import com.qualitygate.domain.model.WaiverScope;
import com.qualitygate.domain.model.WaiverStatus;
import com.qualitygate.domain.repo.FindingRepository;
import com.qualitygate.domain.repo.MonitoredRepositoryRepository;
import com.qualitygate.domain.repo.RepositorySummaryRepository;
import com.qualitygate.domain.repo.UserAccountRepository;
import com.qualitygate.domain.repo.WaiverRepository;
import com.qualitygate.job.JobEnqueuer;
import com.qualitygate.platform.audit.AuditAction;
import com.qualitygate.platform.audit.AuditLogger;
import com.qualitygate.platform.error.ApiException;
import com.qualitygate.platform.error.ErrorCode;
import com.qualitygate.platform.id.Uuid7;
import com.qualitygate.platform.security.Actor;
import com.qualitygate.platform.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 免除の登録・失効・期限切れ（FR-10）。
 *
 * <p>Phase 1 は登録即有効（申請即承認）。承認という手続きより、
 * 「誰が、何を、なぜ、いつまで見逃すと決めたか」の記録を必ず残すことに価値を置く。
 * 登録・失効・期限切れはすべて監査ログに記録する（FR-10-5）。
 */
@Service
public class WaiverService {

    private static final Logger log = LoggerFactory.getLogger(WaiverService.class);

    /** 期限の上限（FR-10-2）。 */
    static final Duration MAX_TERM = Duration.ofDays(90);
    /** 期限切れが近いとみなす残り日数（FR-10-4）。 */
    public static final Duration EXPIRING_SOON = Duration.ofDays(7);
    private static final String TARGET = "WAIVER";

    private final WaiverRepository waivers;
    private final MonitoredRepositoryRepository repositories;
    private final RepositorySummaryRepository summaries;
    private final FindingRepository findings;
    private final UserAccountRepository users;
    private final JobEnqueuer jobs;
    private final CurrentUser currentUser;
    private final AuditLogger auditLogger;

    @SuppressWarnings("java:S107")
    public WaiverService(WaiverRepository waivers, MonitoredRepositoryRepository repositories,
                         RepositorySummaryRepository summaries, FindingRepository findings,
                         UserAccountRepository users, JobEnqueuer jobs, CurrentUser currentUser,
                         AuditLogger auditLogger) {
        this.waivers = waivers;
        this.repositories = repositories;
        this.summaries = summaries;
        this.findings = findings;
        this.users = users;
        this.jobs = jobs;
        this.currentUser = currentUser;
        this.auditLogger = auditLogger;
    }

    @Transactional(readOnly = true)
    public WaiverDtos.WaiverList list(UUID repositoryId, WaiverStatus status) {
        Instant now = Instant.now();
        List<Waiver> all = repositoryId == null
                ? waivers.findAllByOrderByCreatedAtDesc()
                : waivers.findByRepositoryIdOrderByCreatedAtDesc(repositoryId);
        List<Waiver> filtered = all.stream()
                .filter(w -> status == null || w.getStatus() == status)
                // 期限の近いものを先頭に。放置すると次の判定で不合格に戻るものから見る
                .sorted(java.util.Comparator
                        .comparing((Waiver w) -> w.getStatus() != WaiverStatus.ACTIVE)
                        .thenComparing(Waiver::getExpiresAt))
                .toList();

        Map<UUID, String> repositoryNames = new HashMap<>();
        repositories.findAll().forEach(r -> repositoryNames.put(r.getId(), r.fullName()));
        Map<UUID, String> logins = new HashMap<>();
        users.findAll().forEach(u -> logins.put(u.getId(), u.getGithubLogin()));

        List<WaiverDtos.WaiverItem> items = filtered.stream()
                .map(w -> toItem(w, repositoryNames, logins, now)).toList();
        long active = all.stream().filter(w -> w.isEffectiveAt(now)).count();
        long soon = all.stream().filter(w -> isExpiringSoon(w, now)).count();
        return new WaiverDtos.WaiverList(items, active, soon);
    }

    /**
     * 免除を登録する。登録と同時に、当該リポジトリの最新 Run の再評価を積む。
     * 登録したのにダッシュボードが不合格のままだと、登録が効いたのか分からないため。
     */
    @Transactional
    public Waiver create(WaiverDtos.CreateWaiverRequest request) {
        Actor actor = currentUser.actor();
        Instant now = Instant.now();
        MonitoredRepository repository = repositories.findById(request.repositoryId())
                .orElseThrow(() -> ApiException.notFound("リポジトリ", request.repositoryId()));
        validateTerm(request.expiresAt(), now);
        String title = validateTarget(request, repository);

        Waiver waiver = waivers.save(new Waiver(Uuid7.generate(), repository.getId(),
                request.scope(), request.metricId(), request.fingerprint(), title,
                request.reasonCategory(), request.reason().strip(), actor.userId(), now,
                request.expiresAt()));
        auditLogger.record(actor, AuditAction.WAIVER_CREATED, TARGET, waiver.getId(), null,
                snapshot(waiver));
        requestReevaluation(repository.getId(), "waiver-created");
        return waiver;
    }

    /** 失効させる。既に有効でなければ何もしない（冪等）。 */
    @Transactional
    public void revoke(UUID waiverId) {
        Actor actor = currentUser.actor();
        Waiver waiver = waivers.findById(waiverId)
                .orElseThrow(() -> ApiException.notFound("免除", waiverId));
        if (waiver.getStatus() != WaiverStatus.ACTIVE) {
            return;
        }
        Map<String, Object> before = snapshot(waiver);
        waiver.revoke(actor.userId(), Instant.now());
        auditLogger.record(actor, AuditAction.WAIVER_REVOKED, TARGET, waiverId, before,
                snapshot(waiver));
        requestReevaluation(waiver.getRepositoryId(), "waiver-revoked");
    }

    /**
     * 期限を過ぎた免除を EXPIRED にする（日次バッチ）。判定では期限を過ぎた時点で
     * 効かなくなっているが、状態を揃えて記録を残し、最新 Run を再評価する。
     *
     * @return 期限切れにした免除
     */
    @Transactional
    public List<Waiver> expireOverdue(Instant now) {
        List<Waiver> overdue = waivers.findByStatusAndExpiresAtLessThanEqual(
                WaiverStatus.ACTIVE, now);
        for (Waiver waiver : overdue) {
            Map<String, Object> before = snapshot(waiver);
            waiver.expire();
            auditLogger.record(Actor.SYSTEM, AuditAction.WAIVER_EXPIRED, TARGET, waiver.getId(),
                    before, snapshot(waiver));
        }
        overdue.stream().map(Waiver::getRepositoryId).distinct()
                .forEach(repositoryId -> requestReevaluation(repositoryId, "waiver-expired"));
        if (!overdue.isEmpty()) {
            log.info("期限切れの免除を無効化しました 件数={}", overdue.size());
        }
        return overdue;
    }

    /** 期限 7 日前に入った有効な免除（通知の対象）。 */
    @Transactional(readOnly = true)
    public List<Waiver> expiringSoon(Instant now) {
        return waivers.findExpiringBetween(now, now.plus(EXPIRING_SOON));
    }

    private void requestReevaluation(UUID repositoryId, String trigger) {
        summaries.findById(repositoryId)
                .map(RepositorySummary::getLatestRunId)
                .ifPresent(runId -> jobs.enqueueReevaluation(runId, trigger));
    }

    private static void validateTerm(Instant expiresAt, Instant now) {
        if (!expiresAt.isAfter(now)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "期限は現在より後の日時を指定してください（無期限・過去日の免除は登録できません）");
        }
        if (expiresAt.isAfter(now.plus(MAX_TERM))) {
            throw new ApiException(ErrorCode.WAIVER_EXPIRY_TOO_FAR,
                    "期限は最長 90 日先までです。延長が必要なら期限前に改めて登録してください");
        }
    }

    /**
     * 免除の対象を確かめ、見出しを返す。
     *
     * <p>違反の免除は、そのリポジトリで実際に検出された違反だけを対象にできる。
     * 存在しない fingerprint への免除は、何も見逃さないまま「免除中 1 件」を増やす。
     */
    private String validateTarget(WaiverDtos.CreateWaiverRequest request,
                                  MonitoredRepository repository) {
        String metricName = MetricCatalog.of(request.metricId()).name();
        if (request.scope() == WaiverScope.METRIC) {
            if (request.fingerprint() != null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "指標の免除（scope=METRIC）に fingerprint は指定できません");
            }
            if (!waivers.findByRepositoryIdAndScopeAndMetricIdAndStatus(repository.getId(),
                    WaiverScope.METRIC, request.metricId(), WaiverStatus.ACTIVE).isEmpty()) {
                throw new ApiException(ErrorCode.WAIVER_ALREADY_EXISTS,
                        "%s には有効な指標の免除が既にあります".formatted(metricName));
            }
            return "%s（指標全体）".formatted(metricName);
        }

        if (request.fingerprint() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "違反の免除（scope=FINDING）には fingerprint が必要です");
        }
        if (!waivers.findByRepositoryIdAndScopeAndMetricIdAndFingerprintAndStatus(
                repository.getId(), WaiverScope.FINDING, request.metricId(),
                request.fingerprint(), WaiverStatus.ACTIVE).isEmpty()) {
            throw new ApiException(ErrorCode.WAIVER_ALREADY_EXISTS,
                    "この違反には有効な免除が既にあります");
        }
        return findings.findLatestInRepository(repository.getId(), request.metricId(),
                        request.fingerprint())
                .map(Finding::getTitle)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "このリポジトリで %s の該当する違反が見つかりません".formatted(metricName)));
    }

    private static boolean isExpiringSoon(Waiver waiver, Instant now) {
        return waiver.isEffectiveAt(now)
                && !waiver.getExpiresAt().isAfter(now.plus(EXPIRING_SOON));
    }

    private static WaiverDtos.WaiverItem toItem(Waiver waiver, Map<UUID, String> repositoryNames,
                                                Map<UUID, String> logins, Instant now) {
        return new WaiverDtos.WaiverItem(waiver.getId(), waiver.getRepositoryId(),
                Objects.requireNonNullElse(repositoryNames.get(waiver.getRepositoryId()), ""),
                waiver.getScope(), waiver.getMetricId(),
                MetricCatalog.of(waiver.getMetricId()).name(), waiver.getFingerprint(),
                waiver.getTitle(), waiver.getReasonCategory(),
                waiver.getReasonCategory().label(), waiver.getReason(),
                waiver.isEffectiveAt(now) || waiver.getStatus() != WaiverStatus.ACTIVE
                        ? waiver.getStatus() : WaiverStatus.EXPIRED,
                logins.get(waiver.getCreatedBy()), waiver.getCreatedAt(), waiver.getExpiresAt(),
                isExpiringSoon(waiver, now), waiver.getRevokedAt(),
                waiver.getRevokedBy() == null ? null : logins.get(waiver.getRevokedBy()));
    }

    private static Map<String, Object> snapshot(Waiver waiver) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("repositoryId", waiver.getRepositoryId().toString());
        values.put("scope", waiver.getScope().name());
        values.put("metricId", waiver.getMetricId());
        if (waiver.getFingerprint() != null) {
            values.put("fingerprint", waiver.getFingerprint());
        }
        values.put("title", waiver.getTitle());
        values.put("reasonCategory", waiver.getReasonCategory().name());
        values.put("reason", waiver.getReason());
        values.put("expiresAt", waiver.getExpiresAt().toString());
        values.put("status", waiver.getStatus().name());
        return values;
    }
}
