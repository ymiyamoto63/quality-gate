-- ファイルの移動・リネームの対応表（新しいパス → 移動前のパス。リポジトリ相対）。
--
-- 移動しただけの関数の複雑度違反を「新規」と誤判定しないため、fingerprint を移動前のパスでも引けるようにする
-- （指標仕様書 0.4）。対応表は GitHub の compare API（git の rename 検出）で求め、再評価で同じ結果になるよう
-- Run に保持する。求めていない（GitHub API を使わない設定・失敗した）Run では NULL。
ALTER TABLE runs ADD COLUMN renamed_files jsonb;
