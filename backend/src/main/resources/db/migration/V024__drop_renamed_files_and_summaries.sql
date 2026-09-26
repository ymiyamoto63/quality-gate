-- Run に保持していたファイルの移動の対応表と、ダッシュボード用の集計テーブルを削除する。
--
-- runs.renamed_files: ファイルの移動は比較元からの差分だけを追うことにし、判定のたびに成果物（git-renames）から読む。
--   成果物は変わらないため、Run に保持しなくても再評価で同じ結果になる。
-- repository_summaries: 対象 1〜5 リポジトリの規模では、最新の判定済み Run と最後の完全計測を runs から
--   その場で引けば足りる。判定のたびに集計を更新して整合を保つ処理を持たない。
ALTER TABLE runs DROP COLUMN renamed_files;
DROP TABLE repository_summaries;

-- ダッシュボードとリポジトリ詳細は、リポジトリごとの最新の判定済み Run を引く
CREATE INDEX ix_runs_latest ON runs (repository_id, measured_at DESC, attempt DESC) WHERE status = 'EVALUATED';
