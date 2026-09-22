-- M-02 ミューテーションスコアのための変更。
--
-- 1. 計測条件（variant）を持たせる。
--    M-02 は実行範囲（変更クラスのみ / 全量）によって値の意味が変わる。
--    変更範囲だけの値と全量の値は比較できないため、前回比とトレンドの系列を
--    この列で分ける。detail（jsonb）に入れず列にするのは、トレンド検索で
--    系列の軸として使うためである。条件の区別が無い指標では NULL。
ALTER TABLE measurements ADD COLUMN variant varchar(16);

DROP INDEX ux_measurements_key;
CREATE UNIQUE INDEX ux_measurements_key ON measurements
    (run_id, metric_id, COALESCE(component_name, ''), COALESCE(scenario, ''),
     COALESCE(variant, ''));

-- 2. 「対象外」（NOT_APPLICABLE）を判定ステータスに加える。
--    PIT は JVM 専用のため、frontend では M-02 を測りようがない。
--    これを SKIP（今回は測らなかった）と同じにすると未計測の積み残しに見えるため、
--    別のステータスとして区別する（docs/02-metrics-spec.md M-02）。
--    'NOT_APPLICABLE' は 14 文字のため、列の幅も広げる。
ALTER TABLE measurements DROP CONSTRAINT measurements_status_check;
ALTER TABLE measurements ALTER COLUMN status TYPE varchar(16);
ALTER TABLE measurements ADD CONSTRAINT measurements_status_check CHECK (status IN
    ('PASS','WARN','FAIL','SKIP','REFERENCE','ERROR','NOT_APPLICABLE'));
