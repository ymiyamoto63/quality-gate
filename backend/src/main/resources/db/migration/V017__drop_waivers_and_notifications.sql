-- 免除（Waiver）とメール通知を廃止し、日次バッチを保持期間の削除と滞留した Run の後始末だけにする（D-22）。
--
-- 違反や指標を判定から外すときは、合格ラインの設定（collector/targets/<owner>__<name>.gate.yml）の
-- exclusions・指標の enabled・しきい値を変える。変更はコミットとして残る。

-- 処理する側が無くなったジョブを消す
DELETE FROM jobs WHERE type IN ('SEND_NOTIFICATION', 'DAILY_REEVALUATION', 'EXPIRE_WAIVERS', 'CHECK_FRESHNESS');

ALTER TABLE findings DROP COLUMN waiver_id;
ALTER TABLE repository_summaries DROP COLUMN active_waiver_count;
DROP TABLE waivers;

DROP TABLE notifications;
DROP TABLE notification_settings;
-- 通知の「合格 → 不合格」の遷移判定にだけ使っていた
ALTER TABLE runs DROP COLUMN previous_verdict;

-- 保持期間の設定から通知の送信履歴の項目を取り除く
UPDATE system_settings SET value = value - 'notificationDays' WHERE key = 'retention';

-- 保存済みの設定から削除したキーを取り除く。未知のキーは検証エラーになり、残すと判定できなくなる。
--   notifications:                          トップレベルのブロック（続く字下げ行まで）
--   execution.full_measurement_interval_days: 1 行
-- content_hash は変更前の内容のまま残す（V016 と同じ）。
UPDATE gate_configs
SET raw_yaml = regexp_replace(
        regexp_replace(raw_yaml, '^notifications:[^\n]*(\n[ \t][^\n]*)*\n?', '', 'gn'),
        '^[ \t]+full_measurement_interval_days:.*(\n|$)', '', 'gn'),
    parsed = parsed #- '{execution,fullMeasurementIntervalDays}'
WHERE raw_yaml ~ '(^|\n)(notifications|[ \t]+full_measurement_interval_days):'
   OR parsed #> '{execution,fullMeasurementIntervalDays}' IS NOT NULL;
