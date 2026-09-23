-- 免除の対象を人が読める形で残す。
--
-- 免除は fingerprint（ハッシュ）で違反を指すため、それだけでは一覧で
-- 「何を見逃すと決めたのか」が読めない。登録時点の違反の見出し
-- （CVE-2026-1234: example-lib の任意コード実行 など）を複製して持つ。
-- 違反を含む Run が保持期間で消えても、免除の記録は読めるままにするためである。
ALTER TABLE waivers ADD COLUMN title varchar(512);

-- 再評価の直前の判定を残す。通知の「合格 → 不合格」の遷移判定に使う
-- （再評価で verdict を上書きすると、遷移の起点が失われる）。
ALTER TABLE runs ADD COLUMN previous_verdict varchar(24);
ALTER TABLE runs ADD CONSTRAINT runs_previous_verdict_check CHECK (previous_verdict IS NULL
    OR previous_verdict IN ('PASS','PASS_WITH_WARNINGS','FAIL'));
