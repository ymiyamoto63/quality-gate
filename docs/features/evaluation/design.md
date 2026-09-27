# 判定: 設計

要件は [requirements.md](requirements.md)。指標ごとの計算と判定の規則は [指標](../../metrics.md)。

## 1. 判定を実行するとき

| きっかけ | 呼び出し元 |
| --- | --- |
| 取り込みの確定（`POST /api/v1/runs/{id}/finalize`） | 収集ランナー（Ingest Token） |
| 再評価（`POST /api/v1/runs/{id}/reevaluate`） | ADMIN（画面の Run 詳細）。監査ログに `RUN_REEVALUATION_REQUESTED` を残す |

どちらも同じ処理（`RunEvaluationPipeline`）をその場で実行し、結果を応答で返す（DD-15）。判定は保存済みの成果物を読んで DB に書くだけで、外部の API を呼ばない。
自動の再試行はしない。同じ結果になる失敗が大半で、原因を直した後に管理者が再評価すればよい。

確定していない Run の再評価は `409 RUN_NOT_EVALUABLE`、成果物が保持期間で消えた Run の再評価は `409 ARTIFACTS_DELETED`。

## 2. 手順

```
1. 合格ラインの解決   Run の quality-gate-config を読み、検証する（合格ライン）
2. 正規化            成果物を 1 件ずつアダプタでパースし、正規化モデル（指標の素の値と違反）へ変換する
3. fingerprint      違反に fingerprint を付ける（定義を 1 か所に集めるため、アダプタではなく normalize が付ける）
4. 比較対象の特定     比較元コミットの Run、無ければ同じブランチの直前の Run
5. 判定             有効な指標ごとに判定器（MetricEvaluator）を適用する
6. 差分             比較対象 Run と fingerprint を比べ、違反を新規 / 継続 / 解消に分ける
7. 集約・保存        verdict と completeness を決め、measurements / findings を置き換える
```

1〜3 はトランザクションの外で、4〜7 を 1 つのトランザクションで行う（その間は Run の行をロックする）。
ファイルを読む間 DB のトランザクションを保持せず、途中で失敗した Run に中途半端な判定結果を残さないためである。
同じ Run の判定は行ロックで直列化され、再評価は判定結果を置き換える。

## 3. 失敗の扱い

| 失敗 | 扱い |
| --- | --- |
| 合格ラインの検証エラー | Run を `FAILED`（`CONFIG_VALIDATION_FAILED`）にし、行番号つきの理由を記録する |
| 成果物の形式不正 | Run は失敗させず、その指標を `ERROR` にする（fail-closed） |
| 想定外の例外 | Run を `FAILED`（`EVALUATION_FAILED`）にし、ERROR ログを出す |

## 4. アダプタ

アダプタ（`ArtifactAdapter`）は成果物を読んで、指標の素の値と違反を返すだけで、DB にもしきい値にも触れない。

| アダプタ | `type` | 指標 |
| --- | --- | --- |
| `JacocoXmlAdapter` / `LcovAdapter` | `jacoco-xml` / `lcov` | M-01 |
| `PitXmlAdapter` | `pit-xml` | M-02 |
| `K6SummaryAdapter` | `k6-summary` | M-03 / M-04 |
| `SarifAdapter` | `sarif` | M-05 / M-11 / M-12（複雑度ツールの run は読み飛ばす） |
| `PmdXmlAdapter` / `EslintJsonAdapter` | `pmd-xml` / `eslint-json` | M-06 |
| `OasdiffJsonAdapter` | `oasdiff-json` | M-07 |
| `AxeJsonAdapter` | `axe-json` | M-08 |
| `JUnitXmlAdapter` | `test-junit-xml` | M-09 / M-10 |

XML は外部実体参照と外部 DTD を無効にして読み（`SafeXml`）、JSON は深さとサイズに上限を設ける。いずれもストリーミングで読む。

## 5. 指標ごとの判定

判定するのは、合格ラインで有効な指標だけ。各指標は次の順に見て、先に当たったものを結果にする。

```
1. スキップの申告がある                → SKIP（skippable_metrics に含まれる）/ ERROR（含まれない）
2. 成果物を解釈できなかった            → ERROR
3. 成果物が未提出                     → ERROR（申告の無い未提出は不合格。fail-closed）
4. 判定器が値を取り出せなかった         → ERROR
5. 判定器が合格ラインに照らして判定      → PASS / WARN / FAIL / ERROR / NOT_APPLICABLE（指標ごとの規則）
```

判定器は指標ごとに 1 つで、コンポーネントや計測条件ごとに複数の結果を返しうる。前回値は比較対象 Run の同じ指標・コンポーネント・計測条件の値で、判定時に `previous_value` として焼き付ける。

Run 全体の集約と部分計測の規則は [指標](../../metrics.md#21-判定ステータス)。

## 6. 違反の新規 / 継続 / 解消

```
C = 今回の Run の fingerprint、B = 比較対象 Run の fingerprint（解消済みを除く）
NEW = C \ B、CONTINUING = C ∩ B、RESOLVED = B \ C
比較対象 Run が無い（初回）→ すべて INITIAL
```

- 解消した違反も今回の Run に `RESOLVED` として保存する。Run が不変のスナップショットになり、比較対象 Run が消えても表示が壊れない
- 初回にすべてを NEW にすると「この変更が 300 件の問題を持ち込んだ」という誤った印象を与えるため、`INITIAL` で区別する

## 7. 比較対象 Run

1. 再評価では、前回決めた比較対象を使い続ける（後から計測された Run と比べると、過去の Run の「新規 / 解消」が変わってしまう）
2. 比較元コミット（`baseCommitSha`）で判定済みの Run があれば、その最新の attempt
3. 無ければ、同じブランチで、この Run より前に計測された判定済みの Run

比較元コミットを優先するのは、計測が手動で順不同になるため（DD-7 / DD-17）。リリースのタグ v1.1.0 を計測するとき、比較元は前のタグ v1.0.0 で、
「同じブランチで直前に計測した Run」は v1.1.0 より新しいコミットのこともある。
