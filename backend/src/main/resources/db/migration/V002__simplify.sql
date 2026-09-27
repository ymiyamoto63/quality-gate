-- 経営陣にリリースの可否を示すことに目的を絞ったための整理。
--   - ログインは共有の 1 アカウント（環境変数）にし、利用者・ロール・監査ログをやめる
--   - セッションはメモリに持つ（Spring Session JDBC をやめる）
--   - PR の計測とスキップの申告（部分計測）をやめる
--   - 合否を 2 値にする（注意 WARN / 合格（注意あり）PASS_WITH_WARNINGS をやめる）
--   - 合格ラインは環境変数で持つ（Run ごとに送っていた設定ファイルをやめる）

DROP TABLE audit_logs;
DROP TABLE users;
DROP TABLE run_skipped_metrics;
DROP TABLE SPRING_SESSION_ATTRIBUTES;
DROP TABLE SPRING_SESSION;

-- PR の計測は結果ごと消す（リリース判定には使わない）
DELETE FROM runs WHERE pull_request_number IS NOT NULL;

-- 合格ラインの設定ファイルは成果物として扱わない（ファイルの実体は判定に使われなくなる）
DELETE FROM artifacts WHERE type = 'quality-gate-config';

-- 注意は合格に、未計測（スキップ）は計測エラー（不合格）に寄せる。測っていないものを合格とはみなさない
ALTER TABLE measurements DROP CONSTRAINT measurements_status_check;
UPDATE measurements SET status = 'PASS' WHERE status = 'WARN';
UPDATE measurements SET status = 'ERROR' WHERE status = 'SKIP';
ALTER TABLE measurements ADD CONSTRAINT measurements_status_check CHECK (status IN
    ('PASS','FAIL','ERROR','NOT_APPLICABLE'));

ALTER TABLE runs DROP CONSTRAINT runs_verdict_check;
ALTER TABLE runs DROP CONSTRAINT runs_status_check;
UPDATE runs SET verdict = 'PASS' WHERE verdict = 'PASS_WITH_WARNINGS';
UPDATE runs SET verdict = 'FAIL'
    WHERE status = 'EVALUATED'
      AND id IN (SELECT run_id FROM measurements WHERE status IN ('FAIL','ERROR'));
UPDATE runs SET status = 'FAILED', error_code = 'ABANDONED' WHERE status = 'ABANDONED';
ALTER TABLE runs ADD CONSTRAINT runs_verdict_check CHECK (verdict IS NULL OR verdict IN ('PASS','FAIL'));
ALTER TABLE runs ADD CONSTRAINT runs_status_check CHECK (status IN
    ('CREATED','UPLOADING','FINALIZED','PROCESSING','EVALUATED','FAILED'));

ALTER TABLE runs DROP COLUMN pull_request_number;
ALTER TABLE runs DROP COLUMN config_commit_sha;
ALTER TABLE runs DROP COLUMN completeness;

DROP INDEX ix_runs_retention;
