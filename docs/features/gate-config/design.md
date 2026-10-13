# 合格ライン: 設計

要件は [requirements.md](requirements.md)。変数の一覧は [運用](../../operations.md#24-合格ライン)、各しきい値の意味は [指標](../../metrics.md)。

## 1. 置き場所

- `application.yml` の `quality-gate.gate.*` に環境変数を対応づけ、`QualityGateProperties.Gate` に読む。既定値は `Gate` の中だけに持つ（空の環境変数は未設定として既定値にする）
- 判定のたびに `GateThresholds.from(gate)` で判定用の値に直す。判定する指標は 12 の指標から `QG_DISABLED_METRICS` を除いたもの
- 起動時に検証する: `QG_DISABLED_METRICS` の指標 ID（`M-01`〜`M-12`）と数値の形式。誤りがあれば起動に失敗する
- 廃止した環境変数（`QG_SKIPPED_TESTS_INCREASE_MAX`）が残っていれば起動に失敗し、移行先（`QG_SKIPPED_TESTS_MAX`）を示す（`RemovedSettings`）。黙って無視すると、運用者の気づかないうちに判定が変わるため
- M-10 は件数そのものを `QG_SKIPPED_TESTS_MAX` と比べる（[DD-25](../../architecture.md#2-設計判断)）。既存のスキップを認めるときは、上限を今の件数に合わせる
- M-08 の基準（WCAG 2.2 AA）は固定で、環境変数では変えない
- `QG_TEST_COUNT_MIN` は 0 を書かれても 1 とする（0 件の合格は「検証していない」の言い換えにすぎない）

## 2. 判定に使った合格ラインを残す

判定器は結果ごとに `threshold`（`{ "operator", "value", ... }`）を返し、`measurements.threshold` に保存する。
リリース判定の画面の「合格ライン」列はこの値から作るため、環境変数を後から変えても、判定済みの計測はそのときの合格ラインで表示される。

判定から外した指標（`QG_DISABLED_METRICS`）も同じく判定時に `runs.disabled_metrics` に残し、画面の「判定の対象外」に使う（DD-27）。
判定の範囲を狭める残りの設定（`GateThresholds.Scope` の判定する指標以外 = `QG_EXCLUSIONS`・`QG_MUTATION_COMPONENTS`・`QG_PERF_SCENARIOS`・`QG_ACCESSIBILITY_PAGES`）も判定時に `runs.exclusions` / `mutation_components` / `perf_scenarios` / `accessibility_pages` に残し、画面の「判定の条件」に使う（DD-28。[リリース判定 3.2](../release-report/design.md#32-判定の条件conditions)）。

## 3. 既定より緩い合格ラインの印

合格ラインを環境変数で既定値より緩めると、判定時に `measurements.threshold` へ `relaxed: true` と既定の合格ライン `default`（同じ形の JSON）を添える。リリース判定の行と指標の推移の点は、これを `thresholdRelaxed` / `defaultThreshold`（「≥ 75%」など）として返し、画面は「既定より緩い（既定 ≥ 75%）」と出す。

- 緩さは `GateThresholds.withDefaultLimits()`（既定の合格ライン `Limits` に、設定の対象 `Scope` = 判定する指標・除外・M-02 のコンポーネント・M-03 のシナリオ・M-08 の画面を組み合わせたもの）で同じ入力を判定し、同じ指標・コンポーネント・計測条件の結果の合格ラインと比べて決める（[判定 5 章](../evaluation/design.md#5-指標ごとの判定)）
- 向きが `>=` なら小さいほど、`<=` なら大きいほど緩い。比べ方（`basis`）の違うものは比べない。上限・下限の補足（`maxComplexity`・`maxCritical`・`maxHigh`・`minTestCount`・`arrivalRateRps`）も同じ向きで比べる。向きと名称は判定器が `Threshold.withLimit` で宣言し、緩い行にだけ `limits` として保存する（[判定 5 章](../evaluation/design.md#5-指標ごとの判定)）
- 既定より厳しい値には印を付けない（品質の主張を弱めないため）
- 印を足す前に判定した行には印が無く、緩くないものとして表示する
- `QG_EXCLUSIONS` も結果を良く見せうるため、除外したファイルの数（M-01 / M-02 / M-05 / M-06 / M-11 / M-12 の内訳 `excludedFiles`）を行に出し、除外パターンそのものは「判定の条件」に出す（DD-28）。合格ラインの値ではないため「既定より緩い」の印は付けない

## 4. 採らなかった方式

| 方式 | 採らなかった理由 |
| --- | --- |
| 合格ラインのファイル（`*.gate.yml`）を Git で管理し、計測ごとに送る | 送信・検証・表示（設定の画面）の仕組みが要り、アプリが大きくなる。合格ラインは運用者がデプロイの設定として変える方針にした |
| 画面で編集する | 権限（ロール）と変更の監査が要る。閲覧専用の画面に編集の機能は要らない |
| M-10 を比較対象 Run からの増加で判定する（旧 `QG_SKIPPED_TESTS_INCREASE_MAX`） | 比較対象 Run が無い初回は何件スキップしても合格になり、同じコミットの判定がそれまでに何を計測したかで変わる（DD-25）。判定済みの行は `basis: increase` の合格ライン（「前回から +0 件以内」）のまま表示する |
| 「注意」の水準（`warn_below` など） | 示す合否を 2 値にするためやめた。気になる点は判定理由に書き添える |
