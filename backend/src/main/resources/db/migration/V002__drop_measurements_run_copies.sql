-- measurements が Run から複製していた repository_id / measured_at と、それを使う推移用インデックスを外す。
-- 指標の推移は判定済み Run を先に決めて run_id で引くため、どちらも読まれていなかった。
-- リポジトリと計測日時は run_id から runs を辿って得る。

DROP INDEX ix_measurements_trend;
ALTER TABLE measurements DROP COLUMN repository_id;
ALTER TABLE measurements DROP COLUMN measured_at;
