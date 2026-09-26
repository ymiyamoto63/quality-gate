package com.qualitygate.maintenance;

import com.qualitygate.domain.entity.ArtifactRecord;
import com.qualitygate.domain.repo.ArtifactRecordRepository;
import com.qualitygate.platform.settings.RetentionSettings;
import com.qualitygate.platform.settings.SystemSettingsService;
import com.qualitygate.platform.storage.ArtifactStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 保持期間を過ぎたデータの削除（FR-12-3。毎日 {@link ScheduledMaintenance} から呼ぶ）。
 *
 * <p>削除は<strong>少量ずつ、短いトランザクションで</strong>行う（1 回あたり最大 10,000 行）。
 * 一括削除は長時間のロックと WAL の急増を招き、その間アプリが止まる
 * （docs/initial/06-database-design.md 7 章）。
 */
@Component
public class RetentionCleanup {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanup.class);

    static final int BATCH = 10_000;
    static final int FILE_BATCH = 500;
    /** 1 回で回す上限。残りは翌日に回す（長時間走らせない）。 */
    static final int MAX_ROUNDS = 20;
    /** 書き込み途中のファイルを孤児と取り違えないための猶予。 */
    static final Duration ORPHAN_GRACE = Duration.ofDays(1);

    private final ArtifactRecordRepository artifacts;
    private final ArtifactStore artifactStore;
    private final SystemSettingsService settings;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public RetentionCleanup(ArtifactRecordRepository artifacts,
                               ArtifactStore artifactStore, SystemSettingsService settings,
                               JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.artifacts = artifacts;
        this.artifactStore = artifactStore;
        this.settings = settings;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public void run() {
        RetentionSettings retention = settings.retention();
        Instant now = Instant.now();

        int files = deleteArtifactFiles(now.minus(Duration.ofDays(retention.artifactDays())));
        int runs = deleteInBatches("""
                DELETE FROM runs WHERE id IN (
                    SELECT id FROM runs WHERE measured_at < ? ORDER BY measured_at LIMIT ?)
                """, now.minus(Duration.ofDays(retention.runDays())));
        int orphans = deleteOrphanFiles(now.minus(ORPHAN_GRACE));
        int auditLogs = deleteAuditLogs(now.minus(Duration.ofDays(retention.auditLogDays())));

        log.info("保持期間の削除が完了しました 成果物={} Run={} 孤児ファイル={} 監査ログ={}",
                files, runs, orphans, auditLogs);
    }

    /** 成果物のファイル実体を消し、メタデータには削除した事実を残す。 */
    private int deleteArtifactFiles(Instant before) {
        int total = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            Integer deleted = transactions.execute(status -> {
                List<ArtifactRecord> expired = artifacts.findFilesUploadedBefore(before,
                        PageRequest.of(0, FILE_BATCH));
                Instant at = Instant.now();
                for (ArtifactRecord artifact : expired) {
                    artifactStore.delete(artifact.getStorageKey());
                    artifact.markDeleted(at);
                }
                return expired.size();
            });
            total += deleted == null ? 0 : deleted;
            if (deleted == null || deleted < FILE_BATCH) {
                break;
            }
        }
        return total;
    }

    private int deleteInBatches(String sql, Instant before) {
        int total = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            Integer deleted = transactions.execute(status ->
                    jdbc.update(sql, java.sql.Timestamp.from(before), BATCH));
            total += deleted == null ? 0 : deleted;
            if (deleted == null || deleted < BATCH) {
                break;
            }
        }
        return total;
    }

    /**
     * DB に記録の無いファイルを消す。ファイル保存を DB より先に行うため、
     * 保存直後に失敗すると記録の無いファイルが残る（docs/initial/05-architecture.md 3.3）。
     */
    private int deleteOrphanFiles(Instant before) {
        int deleted = 0;
        for (String key : artifactStore.listKeysWrittenBefore(before, FILE_BATCH * 4)) {
            if (!artifacts.existsByStorageKey(key)) {
                artifactStore.delete(key);
                deleted++;
            }
        }
        return deleted;
    }

    /**
     * 監査ログの削除。本番ではアプリのロールから DELETE を剥奪しているため失敗する
     * （管理ロールのバッチが消す。docs/initial/06-database-design.md 3.15）。その場合は警告に留める。
     */
    private int deleteAuditLogs(Instant before) {
        try {
            return deleteInBatches("""
                    DELETE FROM audit_logs WHERE id IN (
                        SELECT id FROM audit_logs WHERE occurred_at < ? LIMIT ?)
                    """, before);
        } catch (DataAccessException e) {
            log.warn("監査ログを削除できませんでした（アプリのロールに削除権限がありません）。"
                    + "管理ロールのバッチで削除してください: {}", e.getMostSpecificCause().getMessage());
            return 0;
        }
    }
}
