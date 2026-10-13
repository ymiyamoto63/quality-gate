-- 判定の範囲を狭める設定を、判定したときの値のまま Run に残す（docs/architecture.md DD-28）。
--
-- exclusions          QG_EXCLUSIONS（計測除外の glob パターン）
-- mutation_components QG_MUTATION_COMPONENTS（M-02 の対象コンポーネント。空なら限定しない）
-- perf_scenarios      QG_PERF_SCENARIOS（M-03 で判定すべきシナリオ。空ならシナリオの有無を確かめない）
-- accessibility_pages QG_ACCESSIBILITY_PAGES（M-08 で検査されているべきページ。空なら限定しない）
--
-- 判定から外した指標（disabled_metrics。V003）と同じく、判定のたびにそのときの設定で上書きし、
-- リリース判定の「判定の条件」に出す。後から設定を変えても、過去の判定の条件が変わって見えないようにするため。
-- 1 つの jsonb にまとめず列を分けて text[] にしたのは、disabled_metrics と同じ形に揃え、
-- 値の形（文字列の一覧）を DB の型で保つため（JSON の形の版管理が要らない）。
-- この列を足す前に判定した Run では NULL（分からない）。画面は「記録なし」と出す。
ALTER TABLE runs ADD COLUMN exclusions text[];
ALTER TABLE runs ADD COLUMN mutation_components text[];
ALTER TABLE runs ADD COLUMN perf_scenarios text[];
ALTER TABLE runs ADD COLUMN accessibility_pages text[];
