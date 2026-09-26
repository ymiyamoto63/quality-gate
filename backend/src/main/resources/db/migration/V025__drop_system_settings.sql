-- 保持期間の設定を環境変数（QG_RETENTION_RUN_DAYS / QG_RETENTION_ARTIFACT_DAYS / QG_RETENTION_AUDIT_LOG_DAYS）に移し、
-- 画面から変更するための system_settings を削除する。
--
-- 保持期間は変える頻度がほとんど無く、テーブル・API・画面・監査の一式に見合わない。
-- 画面で既定値（730 / 90 / 730 日）から変えていた場合は、適用前に同じ値を環境変数に設定すること。
-- 設定しないと既定値に戻る（短くしていた場合は削除が遅れるだけで、データは失われない）。
DROP TABLE system_settings;
