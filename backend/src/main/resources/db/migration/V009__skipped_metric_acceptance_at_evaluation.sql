-- スキップ申告の「受理したか」を判定時に決めるようにする。
--
-- 取り込み時点では、どの指標のスキップを許容するか（execution.skippable_metrics）が
-- まだ分からない。設定は成果物として後から届くためである。
-- 取り込み時に暫定値を入れると、設定と食い違ったまま記録が残る。
--
-- そこで accepted を NULL 許容にし、
--   NULL   = 申告は受け取ったが、まだ判定していない
--   true   = 設定が許容しており SKIP とした
--   false  = 設定が許容しておらず ERROR とした
-- の 3 状態を表現する。
ALTER TABLE run_skipped_metrics ALTER COLUMN accepted DROP NOT NULL;
ALTER TABLE run_skipped_metrics ALTER COLUMN accepted DROP DEFAULT;

COMMENT ON COLUMN run_skipped_metrics.accepted IS
    'NULL=未判定 / true=設定が許容し SKIP / false=設定が許容せず ERROR';
