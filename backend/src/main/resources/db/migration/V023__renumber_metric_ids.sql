-- 指標 ID を連番に振り直す（M-09〜M-14 → M-08〜M-13）。
--
-- 一意制約（run_skipped_metrics の主キー、measurements の ux_measurements_key）に
-- 途中の行がぶつからないよう、若い番号から 1 つずつ移す（移し先は直前の文で空いている）。
-- 違反の fingerprint は指標 ID を含むハッシュで、元の値が残っていないため作り直せない。
-- 移した後の最初の判定では、比較対象 Run の M-08〜M-13 の違反が新規 / 解消として出る
-- （比較対象 Run を再評価すれば、保存済みの成果物から新しい ID で作り直される）。

UPDATE measurements SET metric_id = 'M-08' WHERE metric_id = 'M-09';
UPDATE findings SET metric_id = 'M-08' WHERE metric_id = 'M-09';
UPDATE run_skipped_metrics SET metric_id = 'M-08' WHERE metric_id = 'M-09';

UPDATE measurements SET metric_id = 'M-09' WHERE metric_id = 'M-10';
UPDATE findings SET metric_id = 'M-09' WHERE metric_id = 'M-10';
UPDATE run_skipped_metrics SET metric_id = 'M-09' WHERE metric_id = 'M-10';

UPDATE measurements SET metric_id = 'M-10' WHERE metric_id = 'M-11';
UPDATE findings SET metric_id = 'M-10' WHERE metric_id = 'M-11';
UPDATE run_skipped_metrics SET metric_id = 'M-10' WHERE metric_id = 'M-11';

UPDATE measurements SET metric_id = 'M-11' WHERE metric_id = 'M-12';
UPDATE findings SET metric_id = 'M-11' WHERE metric_id = 'M-12';
UPDATE run_skipped_metrics SET metric_id = 'M-11' WHERE metric_id = 'M-12';

UPDATE measurements SET metric_id = 'M-12' WHERE metric_id = 'M-13';
UPDATE findings SET metric_id = 'M-12' WHERE metric_id = 'M-13';
UPDATE run_skipped_metrics SET metric_id = 'M-12' WHERE metric_id = 'M-13';

UPDATE measurements SET metric_id = 'M-13' WHERE metric_id = 'M-14';
UPDATE findings SET metric_id = 'M-13' WHERE metric_id = 'M-14';
UPDATE run_skipped_metrics SET metric_id = 'M-13' WHERE metric_id = 'M-14';
