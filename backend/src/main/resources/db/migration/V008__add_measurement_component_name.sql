-- measurements にコンポーネント名を持たせる。
--
-- 設計時は components への FK（component_id）のみを想定していたが、
-- 表示・トレンドの絞り込みでは常にコンポーネント名が必要であり、
-- findings は既に component_name を非正規化して持っている。
-- 両者で表現が揃っていないと、同じ「backend」を指すのに
-- 片方は FK を辿り、片方は文字列という不整合な扱いになる。
--
-- component_id は将来のコンポーネント管理機能のために残す。
ALTER TABLE measurements ADD COLUMN component_name varchar(64);

-- 一意性の定義も component_name を含める形に置き換える。
-- component_id が未登録のうちは NULL であり、旧定義では
-- backend と frontend の M-01 が衝突してしまう。
DROP INDEX ux_measurements_key;
CREATE UNIQUE INDEX ux_measurements_key ON measurements
    (run_id, metric_id, COALESCE(component_name, ''), COALESCE(scenario, ''));
