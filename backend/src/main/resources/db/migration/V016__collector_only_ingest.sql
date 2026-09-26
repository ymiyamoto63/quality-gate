-- 取り込み経路を収集ランナーに絞る。
--
-- 収集ランナーは常に専有のセルフホストランナーで計測するため、ランナー種別（self-hosted / github-hosted）で
-- 性能値を参考値に落とす仕組み（D-13）と、判定結果を GitHub の Check Run として出す仕組み（enforcement）を削除した。
ALTER TABLE runs DROP COLUMN runner_type;

-- Check Run を出すジョブは処理する側が無くなったため消す
DELETE FROM jobs WHERE type = 'PUBLISH_CHECK_RUN';

-- 保存済みの設定から削除したキーを取り除く。未知のキーは検証エラーになり、残すと判定できなくなる。
--   enforcement:                            トップレベルの 1 行
--   execution.reference_only_environments:  フロー形式（[a, b]）の 1 行か、続くブロック形式の要素（- a）まで
-- content_hash は変更前の内容のまま残す（同じ内容を画面で保存し直したときに新しい版になるだけで、判定には影響しない）。
UPDATE gate_configs
SET raw_yaml = regexp_replace(
        regexp_replace(raw_yaml, '^enforcement:.*(\n|$)', '', 'gn'),
        '^[ \t]+reference_only_environments:.*(\n[ \t]*-.*)*(\n|$)', '', 'gn'),
    parsed = (parsed - 'enforcement') #- '{execution,referenceOnlyEnvironments}'
WHERE raw_yaml ~ '(^|\n)(enforcement|[ \t]+reference_only_environments):'
   OR parsed ? 'enforcement';
