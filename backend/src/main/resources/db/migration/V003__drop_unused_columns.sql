-- 使われなくなった列を消す。
--   - measurements.scenario: 性能のシナリオは detail に持ち、この列には書いていない
--   - artifacts.deleted_at: 成果物を自動で消す仕組み（保持期間）は持たない

-- 一意性の式インデックスが scenario を含むため、列を消す前に作り直す
DROP INDEX ux_measurements_key;
ALTER TABLE measurements DROP COLUMN scenario;
CREATE UNIQUE INDEX ux_measurements_key ON measurements
    (run_id, metric_id, COALESCE(component_name, ''), COALESCE(variant, ''));

ALTER TABLE artifacts DROP COLUMN deleted_at;
