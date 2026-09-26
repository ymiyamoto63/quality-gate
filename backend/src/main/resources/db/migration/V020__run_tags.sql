-- 計測したコミットを指すタグを Run に持たせる（D-26）。
--
-- リリース判定（S-11）でタグをコミットに解決するのに使う。これまでは判定のたびに GitHub API で解決していたが、
-- 収集ランナーが計測時に対象リポジトリの履歴から求めて送るようにし、バックエンドから GitHub API の呼び出しをなくした。
-- この版より前の Run はタグを持たないため、タグで指定するにはタグを指定して計測し直すか、コミット SHA で指定する。
ALTER TABLE runs ADD COLUMN tags TEXT[] NOT NULL DEFAULT '{}';
CREATE INDEX ix_runs_tags ON runs USING GIN (tags);
