-- 性能指標（M-03 / M-04 / M-05）のための変更。
--
-- 性能値は計測環境（専有ランナーの名前など）によって比較できるかが決まる。
-- 環境名を計測条件（variant）として持たせ、前回比とトレンドの系列を環境ごとに分ける
-- （docs/02-metrics-spec.md M-03「計測条件」）。
-- 環境名は利用者が付ける名前（perf-staging など）であり、16 文字では足りない。
ALTER TABLE measurements ALTER COLUMN variant TYPE varchar(64);
