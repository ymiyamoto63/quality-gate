-- リポジトリの「PR を計測する」設定を削除する。
--
-- どこからも読まれていなかった。PR を計測するかは収集ランナーの手動実行で PR 番号を指定するかどうかで決まり、
-- 以前の定期実行（D-22 で廃止）も計測プロファイルの MEASURE_PULL_REQUESTS を見ていた。
-- 効かない設定を画面と API に残すと、切り替えたつもりで何も変わらないため削除する。
ALTER TABLE repositories DROP COLUMN measure_pull_requests;
