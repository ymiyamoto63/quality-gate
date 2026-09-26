-- ジョブキューとリポジトリごとの Ingest Token を削除する（D-27）。
--
-- 判定は取り込みの確定と再評価の中でその場で行い、日次バッチは定期実行（@Scheduled）で直接動かす。
-- 非同期にしていた主な理由（判定の途中で GitHub API を呼ぶこと）は D-26 で無くなった。
-- Ingest Token は送り手が収集ランナーだけなので、環境変数 QG_INGEST_TOKEN の 1 つにまとめる。
-- 発行・失効の記録は監査ログ（audit_logs）に残っている。
DROP TABLE jobs;
DROP TABLE ingest_tokens;
