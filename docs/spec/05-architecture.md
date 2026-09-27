# quality-gate 方式設計

本書は、処理の流れ・状態遷移・判定・認証認可・エラー処理といった**アプリケーション全体の動き方**を定める。
前提は [要件定義書](01-requirements.md) と [指標・判定仕様](02-metrics-spec.md)、設計判断の理由は [03](03-design-decisions.md)。

---

## 1. モジュールと依存規則

```
com.qualitygate
├ ingest/       取り込み API、Ingest Token 認証、成果物の受領と保管、リポジトリの登録
├ pipeline/     合格ラインの解決 → 正規化 → 判定の流れ（確定と再評価から呼ぶ）
├ adapter/      ツール別パーサ（jacoco, lcov, pit, k6, sarif, pmd, eslint, junit, oasdiff, axe）
├ normalize/    正規化モデルへの変換、重複排除、fingerprint 生成
├ evaluate/     しきい値の適用、指標の判定、Run の集約、違反の新規 / 継続 / 解消
├ config/       合格ライン（*.gate.yml）の検証と解決、複数モジュールを組み立てる設定（SecurityConfig など）
├ query/        参照系（ダッシュボード・トレンド・一覧）
├ release/      リリース判定と CSV
├ admin/        管理系の操作（利用者・再評価・監査ログ）
├ auth/         GitHub ログイン時の許可リスト照合、セッションのロール更新
├ maintenance/  日次バッチ（保持期間の削除・滞留した Run の後始末）
├ domain/       エンティティ・リポジトリ・正規化モデル・列挙値
└ platform/     監査ログ、ArtifactStore、共通例外、設定
```

| 規則 | 理由 |
| --- | --- |
| `adapter` と `evaluate` は互いを知らない | パーサは読むだけ、判定は正規化モデルだけを入力にする。ツールを差し替えても判定は変わらず、判定基準を変えてもパーサは変わらない |
| `adapter` と `evaluate` は `config` を知らない | 合格ラインの解決は `pipeline` が行い、解決済みの値（`domain.gate`）だけを渡す |
| `query` は書き込み系（`ingest` / `normalize` / `evaluate`）を呼ばない | 参照系は保存済みの判定結果を読むだけ |
| すべてのモジュールが `platform` に依存してよい。逆は不可 | 共通基盤が業務ロジックを知らない状態を保つ |

依存規則は ArchUnit のテスト（`ModuleDependencyTest`）で検証する。バックエンドは GitHub API を呼ばない（DD-10）。

---

## 2. Run のライフサイクル

Run は「1 つのコミットに対する 1 回の計測・判定」。**確定後は不変**で、再評価は判定結果だけを差し替える。

```
CREATED ─(成果物)→ UPLOADING ─(finalize)→ FINALIZED → PROCESSING ─┬→ EVALUATED
   │                   │                                          └→ FAILED（処理の失敗。再評価で直せる）
   └───────────────────┴─(24 時間 finalize されない)→ ABANDONED（終端）
```

- **`FAILED` と `verdict = FAIL` は別物**。前者は quality-gate の処理の失敗（合格ラインの誤りなど）、後者は品質が合格ラインを満たさなかったという判定結果。画面でも混同させない
- `ABANDONED` は収集ランナーが途中で落ちた Run。放置するとダッシュボードの最新 Run が処理中のまま見えるため、日次バッチで終端にする
- 同じコミットへの再送信は `attempt` を増やした新しい Run になる。ダッシュボードとトレンドは最新の attempt を使う

---

## 3. 取り込みから判定まで

```
収集ランナー → POST /runs（リポジトリが無ければ登録）→ 201 {runId}
            → POST /runs/{id}/artifacts ×N（検証して保存。パースはしない）→ 202
            → POST /runs/{id}/finalize → FINALIZED を確定 → その場で判定 → 200 {status, verdict}
```

**確定すると判定まで行い、結果を返す**（DD-15）。判定は保存済みの成果物を読んで DB に書くだけで、外部 API を呼ばない。
確定は判定の前に別のトランザクションで確定させ、判定に失敗しても取り消さない（Run を `FAILED` として残す）。
再評価（`POST /runs/{id}/reevaluate`）も同じ判定をその場で実行する。

### 3.1 判定の手順（`RunEvaluationPipeline`）

```
1. 合格ラインの解決  Run の成果物の quality-gate-config を読み、検証する（4 章）
2. 正規化           成果物を 1 件ずつパースし、正規化モデルへ変換する
3. 重複排除         指標ごとの fingerprint で違反を名寄せする
4. 比較対象の特定    比較元コミットの Run、無ければ同じブランチの直前の Run
5. 判定             指標ごとに MetricEvaluator を適用する（5 章）
6. 差分             比較対象 Run と fingerprint を比べ、違反を新規 / 継続 / 解消に分ける
7. 集約・保存        verdict と completeness を決め、measurements / findings を置き換える
```

1〜3 はトランザクションの外で行い、4〜7 を 1 つのトランザクションで行う（その間は Run の行をロックする）。
DB のトランザクションをファイル読み取りの間保持せず、途中で失敗した Run に中途半端な判定結果を残さないためである。

### 3.2 トランザクションと冪等性

| 対象 | 方式 |
| --- | --- |
| 成果物の保存 | ファイルを先に保存し、成功後に DB へ記録する。逆順だと「参照先の無い記録」という扱いにくい壊れ方をする。残った孤児ファイルは日次バッチで消す |
| 成果物の再送 | `(run_id, type, filename)` で一意。確定前の同じ種別・同じファイル名の再送は置き換え（古いファイルはコミット後に消す） |
| 確定 | 確定済みの Run への `finalize` は 409（二重に判定しない） |
| 再評価 | 同じ Run の判定は行ロックで直列化し、判定結果を置き換える |

### 3.3 失敗の扱い

| 失敗 | 扱い |
| --- | --- |
| 合格ラインの検証エラー | Run を `FAILED`（`CONFIG_VALIDATION_FAILED`）とし、行番号つきの理由を記録する。不正な設定で判定を続けると、意図しないしきい値で合格が出てしまう |
| 成果物の形式不正 | Run は失敗させず、その指標を `ERROR` にする（fail-closed） |
| 想定外の例外 | Run を `FAILED`（`EVALUATION_FAILED`）とし、ERROR ログを出す |

自動の再試行はしない。同じ結果になる失敗が大半で、直した後に管理者が再評価すればよい。

### 3.4 日次バッチ（`ScheduledMaintenance`）

| 処理 | 時刻（既定） | 内容 |
| --- | --- | --- |
| 保持期間の削除 | 毎日 03:00 | 成果物のファイル・Run・孤児ファイル・監査ログ（[06](06-database-design.md) 4 章） |
| 滞留した Run の後始末 | 毎日 03:10 | 24 時間 `finalize` されない Run を `ABANDONED` に |

失敗しても翌日にもう一度動く。単一プロセス構成のため、多重実行の排他は要らない。

---

## 4. 合格ラインの解決

合格ラインは収集ランナーが `quality-gate-config` 型の成果物として Run ごとに送る
（`collector/targets/<owner>__<name>.gate.yml`。置き場所はここだけ。DD-13）。

- Run の成果物に合格ラインがあれば、それを検証して使う。**合格ラインは Run の成果物として残る**（保持期間の削除の対象外）ため、再評価でも同じ合格ラインで判定できる
- 無ければシステムの既定値を使う（収集ランナーは合格ラインが無ければ送信の前に止まるため、既定値になるのは手動で送った Run だけ）
- Run には合格ラインを送った quality-gate リポジトリのコミット（`config_commit_sha`）を記録する。版と変更理由は Git で見る。DB で版を管理しない
- バックエンドが GitHub から合格ラインを取りに行く方式は採らない（GitHub の障害で判定が止まり、認証情報も要るため）

### 4.1 検証

検証エラーは**行番号とキーのパス付き**で記録し、Run 詳細と設定の画面（S-06）で原因が分かるようにする。

| 規則 | 理由 |
| --- | --- |
| 未知のキーはエラー。綴りの近い候補を添える | typo を黙って無視すると、設定したつもりの値が効かないまま合格が出続ける |
| 重複したキーはエラー | 後勝ちにすると、消したはずの設定が効き続ける |
| 割合は 0〜100、件数は 0 以上。文字列で書いた数値（`"75%"`）はエラー | 範囲外や暗黙の変換を通すと、書いた値と効く値がずれる |
| `skippable_metrics` は既知の指標名だけ | 綴りを間違えた指標のスキップが受理されず、原因が分かりにくい ERROR になる |

---

## 5. 正規化と判定

**アダプタ**（`ArtifactAdapter`）は成果物を読んで、指標の素の値（`RawMeasurement`）と違反（`RawFinding`）を返すだけで、
DB にもしきい値にも触れない。fingerprint はアダプタではなく `normalize` が付ける（定義を 1 か所に集めるため）。

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

**判定器**（`MetricEvaluator`）は指標ごとに 1 つで、コンポーネントごとに複数の結果を返しうる。
指標の追加は、アダプタと判定器の追加で完結する。各指標は次の順に判定し、先に当たったものが結果になる。

```
1. 合格ラインで enabled: false        → 判定しない
2. スキップの申告あり                 → SKIP（skippable_metrics に含まれる）/ ERROR（含まれない）
3. 成果物が未提出、または形式不正      → ERROR
4. しきい値に照らして                 → PASS / WARN / FAIL
```

ツールの制約で測りようのないコンポーネント（M-02 の frontend）は `NOT_APPLICABLE` とする。

### 5.1 違反の新規 / 継続 / 解消

```
C = 今回の Run の fingerprint、B = 比較対象 Run の fingerprint
NEW = C \ B、CONTINUING = C ∩ B、RESOLVED = B \ C
比較対象 Run が無い（初回）→ すべて INITIAL
```

解消した違反も今回の Run に `RESOLVED` として保存する。Run が不変のスナップショットになり、比較対象 Run が消えても表示が壊れない。
初回にすべてを NEW にすると「この変更が 300 件の問題を持ち込んだ」という誤った印象を与えるため、`INITIAL` で区別する。

---

## 6. 認証・認可

| 項目 | 方式 |
| --- | --- |
| ログイン | GitHub App の user-to-server 認可フロー（Spring Security の OAuth2 Client）。セッションは Spring Session JDBC で DB に置き、アプリの再起動でログアウトさせない |
| 許可リスト | GitHub の認証が成功しても、`users` に `ACTIVE` で登録されていなければ拒否する。`users` が 1 件も無いときだけ、最初のログイン利用者を `ADMIN` として登録する（監査ログに `BOOTSTRAP_ADMIN`） |
| Ingest Token | 環境変数 `QG_INGEST_TOKEN`（カンマ区切りで新旧を並べると両方受け付ける）。送られた値と設定値をどちらも SHA-256 にしてから定数時間で比べる。未設定なら取り込み API はすべて 401 |
| 認可 | `ADMIN` / `VIEWER`。API のメソッドセキュリティ（`@PreAuthorize`）を唯一の権限境界とし、画面のルーティングガードは利便性のためだけ |

---

## 7. エラー処理と可観測性

- API のエラーは RFC 9457（Problem Details）に `errorCode` を足して返す。`detail` には**何をどう直せばよいか**を書く（取り込みの失敗は収集ランナーのログにしか残らないため）。例: `commitSha は 40 桁の 16 進数で指定してください（受信値: 6ab1e37）`
- ログは JSON 構造化ログ（`QG_LOG_FORMAT`）。`requestId`（`X-Request-Id`）と `runId` を MDC に載せ、エラー応答の `traceId` と一致させる。Ingest Token・セッション ID・GitHub のトークンは出さない
- ログのレベル: 判定結果は INFO、成果物の形式不正は WARN（日常的に起こる正常系のため）、想定外の失敗と日次バッチの失敗は ERROR
- メトリクスは Spring Boot の標準（JVM、HTTP、DB 接続プール）を `/actuator/prometheus` で公開する。読む仕組みが無い独自メトリクスは持たない

---

## 8. 性能

| 要件 | 方式 |
| --- | --- |
| ダッシュボード | リポジトリごとに、最新の判定済み Run と最後の完全計測を部分インデックス（`ix_runs_latest`）で 1 行ずつ引く。集計用のテーブルは持たない（1〜5 リポジトリでは不要で、判定・再評価・削除のたびに整合させる処理が要る） |
| トレンド | `measurements` の `(repository_id, metric_id, measured_at)` の複合インデックスで範囲検索する |
| 確定から判定結果まで | パースをトランザクションの外に出し、書き込みはバッチ INSERT にする |
| 同時実行 | 仮想スレッドで I/O 待ちを占有しない。収集ランナーは 1 台で直列のため、同時の取り込みはほぼ起きない |
