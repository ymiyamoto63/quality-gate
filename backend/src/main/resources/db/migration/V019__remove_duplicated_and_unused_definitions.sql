-- 重複していた定義と指標、判定に使っていなかった設定項目を削除する（D-25）。
--
-- コンポーネント: 計測を分けているのは計測プロファイル（collector/targets/*.env の BACKEND_DIR など）だけで、
-- DB の定義（管理画面で登録）は画面に表示するだけ、設定ファイルの components は読まれていなかった。
-- 判定と表示には成果物に付いたコンポーネント名（measurements.component_name）を使う。
--
-- M-08（API 契約テスト成功率）: 入力が M-11（テスト成功率）の入力の一部で、同じテストの失敗を 2 つの指標で数えていた。
-- テストの成功は M-11、API の互換性は M-09 で見る。
--
-- 設定項目: 受け付けるだけで判定に使っていなかったものを削除する（書いても結果が変わらず、効いていると誤解させる）。
--   on_missing_report / branch_coverage.scope / per_component / mutation_score.scope /
--   cyclomatic_complexity.scope / vulnerabilities.max_medium
-- branch_coverage.diff_threshold は「変更行のカバレッジ」ではなく全体値の注意ラインだったため、warn_below に改める。

ALTER TABLE measurements DROP COLUMN component_id;
DROP TABLE components;

-- 過去の Run の M-08 を消す。指標の定義が無いまま残すと、Run 詳細やトレンドに名前の無い行が並ぶ。
-- 取り込んだ成果物（junit-xml）の行も消す。ファイルは保持期間の処理が孤立したファイルとして削除する。
-- Run の判定（verdict）は計測した時点の結論として残す。
DELETE FROM measurements WHERE metric_id = 'M-08';
DELETE FROM findings WHERE metric_id = 'M-08';
DELETE FROM run_skipped_metrics WHERE metric_id = 'M-08';
DELETE FROM artifacts WHERE type = 'junit-xml';

-- 保存済みの設定から削除したキーを取り除く。未知のキーは検証エラーになり、残すと判定できなくなる（V017 と同じ）。
--   components:                                   トップレベルのブロック（続く字下げ行と「- 」で始まる行まで）
--   metrics.api_contract.min_success_rate / min_test_count: api_contract の中の行だけ（test_results にも同名のキーがある）
-- content_hash は変更前の内容のまま残す（V016 と同じ）。
CREATE FUNCTION pg_temp.qg_v019_strip(yaml text) RETURNS text
LANGUAGE plpgsql AS $$
DECLARE
    line            text;
    kept            text[] := '{}';
    indent          int;
    in_components   boolean := false;
    contract_indent int;          -- api_contract: の字下げ。ブロックの外なら NULL
BEGIN
    FOREACH line IN ARRAY string_to_array(yaml, E'\n') LOOP
        indent := length(line) - length(ltrim(line, ' '));
        IF line ~ '^\s*(#.*)?$' THEN
            -- 空行とコメントは、取り除くブロックの中でも残して害が無い
            kept := kept || line;
            CONTINUE;
        END IF;

        IF in_components AND (indent > 0 OR line ~ '^-') THEN
            CONTINUE;
        END IF;
        in_components := line ~ '^components\s*:';
        IF in_components THEN
            CONTINUE;
        END IF;

        IF contract_indent IS NOT NULL AND indent <= contract_indent THEN
            contract_indent := NULL;
        END IF;
        IF line ~ '^\s+api_contract\s*:\s*(#.*)?$' THEN
            contract_indent := indent;
        ELSIF contract_indent IS NOT NULL AND line ~ '^\s+(min_success_rate|min_test_count)\s*:' THEN
            CONTINUE;
        END IF;

        kept := kept || line;
    END LOOP;
    RETURN array_to_string(kept, E'\n');
END;
$$;

UPDATE gate_configs
SET raw_yaml = pg_temp.qg_v019_strip(raw_yaml),
    parsed = parsed #- '{metrics,api_contract,values,min_success_rate}'
                    #- '{metrics,api_contract,values,min_test_count}'
WHERE raw_yaml ~ '(^|\n)components\s*:'
   OR raw_yaml ~ '(^|\n)\s+api_contract\s*:';

-- 判定に使っていなかった項目を取り除き、diff_threshold を warn_below に改める。
-- scope / per_component / max_medium は指標の中にしか現れず、on_missing_report はトップレベルにしか現れない。
UPDATE gate_configs
SET raw_yaml = regexp_replace(
        regexp_replace(
            regexp_replace(raw_yaml, '^on_missing_report:[^\n]*(\n|$)', '', 'gn'),
            '^[ \t]+(scope|per_component|max_medium)[ \t]*:[^\n]*(\n|$)', '', 'gn'),
        '^([ \t]+)diff_threshold([ \t]*:)', '\1warn_below\2', 'gn'),
    parsed = CASE
        WHEN parsed #> '{metrics,branch_coverage,values,diff_threshold}' IS NULL THEN parsed
        ELSE jsonb_set(parsed, '{metrics,branch_coverage,values,warn_below}',
                       parsed #> '{metrics,branch_coverage,values,diff_threshold}')
    END
        - 'onMissingReport'
        #- '{metrics,branch_coverage,values,diff_threshold}'
        #- '{metrics,branch_coverage,values,scope}'
        #- '{metrics,branch_coverage,values,per_component}'
        #- '{metrics,mutation_score,values,scope}'
        #- '{metrics,cyclomatic_complexity,values,scope}'
        #- '{metrics,vulnerabilities,values,max_medium}'
WHERE raw_yaml ~ '(^|\n)(on_missing_report|[ \t]+(scope|per_component|max_medium|diff_threshold)[ \t]*):'
   OR parsed ? 'onMissingReport';
