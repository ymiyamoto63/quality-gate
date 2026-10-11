-- 判定の範囲を Run に残す（docs/architecture.md DD-25 / DD-26）。
--
-- components       収集ランナーが Run の作成時に申告した、成果物を送るはずのコンポーネント（backend / frontend）。
--                  コンポーネントごとに判定する指標（M-01 / M-02 / M-09 / M-10）で、成果物の届かなかった
--                  コンポーネントを ERROR にするのに使う
-- disabled_metrics 判定したときに QG_DISABLED_METRICS で判定から外していた指標。
--                  リリース判定の画面に「判定の対象外」として出す（合格ラインと同じく判定時に焼き付ける）
--
-- どちらも、この列を足す前に作られた Run では NULL（分からない）。components が NULL の Run では
-- コンポーネントの揃いを確かめず、disabled_metrics が NULL の Run では判定しない指標を「無し」として表示する。
ALTER TABLE runs ADD COLUMN components text[];
ALTER TABLE runs ADD COLUMN disabled_metrics text[];
