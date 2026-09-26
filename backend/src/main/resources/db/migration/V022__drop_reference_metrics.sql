-- 参考値の指標（M-15 コード重複率 / M-16 Lighthouse / M-17 バンドルサイズ）を削除する（D-28）。
--
-- 合格ラインを持たず、合否にもリリース判定にも使っていなかった。ツール 3 つと計測時間に見合う使われ方をしていない。
-- 判定のステータス REFERENCE（参考値）を出すのはこの 3 指標だけだったため、ステータスごと削除する。

-- 過去の Run の計測値・違反・スキップ申告と、取り込んだ成果物の行を消す。
-- ファイルは保持期間の処理が孤立したファイルとして削除する。Run の判定（verdict）は計測した時点の結論として残す。
DELETE FROM measurements WHERE metric_id IN ('M-15', 'M-16', 'M-17');
DELETE FROM findings WHERE metric_id IN ('M-15', 'M-16', 'M-17');
DELETE FROM run_skipped_metrics WHERE metric_id IN ('M-15', 'M-16', 'M-17');
DELETE FROM artifacts WHERE type IN ('jscpd-json', 'lighthouse-json', 'bundle-size-json');

ALTER TABLE measurements DROP CONSTRAINT measurements_status_check;
ALTER TABLE measurements ADD CONSTRAINT measurements_status_check CHECK (status IN
    ('PASS','WARN','FAIL','SKIP','ERROR','NOT_APPLICABLE'));

-- ダッシュボードのカテゴリ別の状態から、参考値だけのカテゴリを外す（次の判定で作り直される）
UPDATE repository_summaries
SET category_status = COALESCE(
        (SELECT jsonb_object_agg(key, value) FROM jsonb_each(category_status) WHERE value <> '"REFERENCE"'),
        '{}'::jsonb)
WHERE category_status IS NOT NULL
  AND EXISTS (SELECT 1 FROM jsonb_each(category_status) WHERE value = '"REFERENCE"');

-- 保存済みの設定から metrics.duplication / lighthouse / bundle_size のブロックを取り除く。
-- 未知のキーは検証エラーになり、残すと判定できなくなる（V017 / V019 と同じ）。
-- content_hash は変更前の内容のまま残す（V016 と同じ）。
CREATE FUNCTION pg_temp.qg_v022_strip(yaml text) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    line         text;
    kept         text[] := '{}';
    indent       int;
    block_indent int;             -- 取り除くブロックの字下げ。ブロックの外なら NULL
BEGIN
    FOREACH line IN ARRAY string_to_array(yaml, E'\n') LOOP
        indent := length(line) - length(ltrim(line, ' '));
        IF block_indent IS NOT NULL THEN
            -- ブロックの中身（より深い字下げの行と空行）は捨てる
            IF btrim(line) = '' OR indent > block_indent THEN
                CONTINUE;
            END IF;
            block_indent := NULL;
        END IF;
        IF line ~ '^\s+(duplication|lighthouse|bundle_size)\s*:' THEN
            block_indent := indent;
            CONTINUE;
        END IF;
        kept := kept || line;
    END LOOP;
    RETURN array_to_string(kept, E'\n');
END
$$;

UPDATE gate_configs
SET raw_yaml = pg_temp.qg_v022_strip(raw_yaml),
    parsed = parsed #- '{metrics,duplication}' #- '{metrics,lighthouse}' #- '{metrics,bundle_size}'
WHERE raw_yaml ~ '(^|\n)\s+(duplication|lighthouse|bundle_size)\s*:'
   OR parsed #> '{metrics}' ?| ARRAY['duplication', 'lighthouse', 'bundle_size'];
