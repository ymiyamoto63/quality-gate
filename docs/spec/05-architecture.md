# quality-gate 方式設計

本書は、処理の流れ・状態遷移・判定の実行・認証認可・エラー処理といった
**アプリケーション全体の動き方**を定める。前提は [要件定義書](01-requirements.md)・[指標・判定仕様](02-metrics-spec.md)・[技術スタック](04-tech-stack.md)、
主な設計判断の理由は [03](03-design-decisions.md) を参照。
テーブル定義は [06](06-database-design.md)、API は [07](07-api-design.md)、
画面は [08](08-screen-design.md) を参照。

---

## 1. レイヤとモジュールの依存規則

```
com.qualitygate
├ ingest/       取り込み API、Ingest Token 認証、成果物の受領と保管
├ pipeline/     設定解決 → 正規化 → 判定の流れ（確定と再評価から呼ぶ）
├ adapter/      ツール別パーサ（jacoco, lcov, pit, k6, sarif, pmd, eslint, junit, oasdiff, axe）
├ normalize/    正規化モデルへの変換、重複排除、fingerprint 生成、ファイルの移動の対応
├ evaluate/     しきい値適用、指標判定、Run 集約、差分（新規 / 継続 / 解消）算出
├ config/       設定ファイル（*.gate.yml）の検証・版管理と、複数モジュールを組み立てる合成点（SecurityConfig など）
├ query/        参照系ユースケース（ダッシュボード・トレンド・一覧）
├ release/      リリース判定（UC-06）と CSV
├ admin/        管理系の操作 API（利用者・リポジトリ・再評価・監査ログ・保持期間）
├ auth/         GitHub ログイン時の許可リスト照合、セッションのロール更新
├ maintenance/  日次バッチ（保持期間の削除・滞留した Run の後始末）
├ domain/       エンティティ・リポジトリ・正規化モデル・列挙値
└ platform/     監査ログ、ArtifactStore、共通例外、設定

バックエンドは GitHub API を呼ばない（ログインの OAuth だけ。DD-10）。
比較元・タグ・ファイルの移動は収集ランナーが対象の履歴から求めて送る。
```

### 依存規則

| 規則 | 理由 |
| --- | --- |
| `adapter` は `evaluate` を知らない | パーサは「ツールの出力を読む」だけの責務に留める。指標の判定基準が変わってもパーサは変わらない |
| `evaluate` は `adapter` を知らない | 判定は正規化モデルだけを入力とする。ツールを差し替えても判定ロジックは変わらない |
| `query` は書き込み系モジュール（`ingest` / `normalize` / `evaluate`）を呼ばない | 参照系は専用の読み取りモデルを持ち、書き込み側の都合に引きずられない |
| すべてのモジュールが `platform` に依存してよい。逆は不可 | 共通基盤が業務ロジックを知らない状態を保つ |
| 複数モジュールを組み立てる設定は `config` に置く | `SecurityConfig` は `auth` と `ingest` の両方を参照する。`platform` に置くと上の規則に違反する |
| `adapter` と `evaluate` は `config` を知らない | 判定とパースは解決済みの設定（`domain.gate`）と `ParseContext` だけを入力とする。設定の解決と組み立ては `pipeline` が行う |

この依存規則は ArchUnit のテスト（`ModuleDependencyTest`）で機械的に検証する。
規則が文書にしか存在しないと、半年後には守られていないためである。

---

## 2. Run のライフサイクル

Run は「1 つのコミットに対する 1 回の計測・判定」を表す。**確定後は不変**とし、
再評価は判定結果（`measurements` / `findings` / `verdict`）だけを差し替える。

### 2.1 状態遷移

```
                POST /runs
                    │
                    ▼
              ┌───────────┐
              │  CREATED  │  メタデータのみ登録。成果物の受領待ち
              └─────┬─────┘
                    │ POST /runs/{id}/artifacts（1 回以上）
                    ▼
              ┌───────────┐
              │ UPLOADING │  成果物を受領中
              └─────┬─────┘
                    │ POST /runs/{id}/finalize
                    ▼
              ┌───────────┐
              │ FINALIZED │  取り込み完了（同じ要求の中で続けて判定する）
              └─────┬─────┘
                    ▼
              ┌───────────┐
              │PROCESSING │  正規化 → 判定
              └─────┬─────┘
            ┌───────┴────────┐
            ▼                ▼
      ┌───────────┐    ┌───────────┐
      │ EVALUATED │    │  FAILED   │  処理自体が失敗（判定 FAIL とは別物）
      └───────────┘    └───────────┘
```

| 状態 | 意味 | 次の状態 |
| --- | --- | --- |
| `CREATED` | Run を作成し `runId` を払い出した直後 | `UPLOADING` / `FINALIZED` / `ABANDONED` |
| `UPLOADING` | 成果物を 1 件以上受領した | `FINALIZED` / `ABANDONED` |
| `FINALIZED` | 取り込み完了を宣言した（判定の直前） | `PROCESSING` |
| `PROCESSING` | 正規化・判定を実行中 | `EVALUATED` / `FAILED` |
| `EVALUATED` | 判定完了。`verdict` が確定している | （再評価で判定結果のみ更新。失敗すれば `FAILED`） |
| `FAILED` | 設定の検証エラーなどで判定できなかった | （管理者の再評価で `EVALUATED` に戻せる） |
| `ABANDONED` | `finalize` されないまま 24 時間経過 | 終端 |

> **`FAILED` と `verdict = FAIL` は別物**である。前者は quality-gate 側の処理失敗、
> 後者は品質が合格ラインを満たさなかったという業務的な判定結果。
> UI でもこの 2 つを混同させない表示にする（[08](08-screen-design.md) 3.3）。

### 2.2 `ABANDONED` を設ける理由

収集ランナーが途中で落ちると `finalize` が呼ばれず、Run が `CREATED` / `UPLOADING` のまま残る。
これを放置すると、ダッシュボードの「最新 Run」が永久に処理中に見えてしまう。

日次バッチで 24 時間以上滞留した Run を `ABANDONED` とし、
ダッシュボードの最新 Run 判定からは除外しつつ、履歴には残す。
成果物は保持期間に従って削除される。

### 2.3 再試行（attempt）

同一コミットへの再送信は、既存 Run を上書きせず `attempt` を増やした新しい Run として記録する（FR-03-4）。
一意キーは `(repository_id, commit_sha, attempt)`。
ダッシュボードとトレンドは、同一コミットについて**最新 attempt のみ**を採用する。

---

## 3. 取り込みから判定までの流れ

### 3.1 シーケンス

```
収集ランナー            Ingest API          ArtifactStore    RunEvaluationPipeline
│                          │                     │                     │
│─ POST /runs ────────────▶│                     │                     │
│                          │─ runs に INSERT     │                     │
│◀─ 201 {runId} ───────────│                     │                     │
│                          │                     │                     │
│─ POST /artifacts (×N) ──▶│                     │                     │
│                          │─ 検証（型/サイズ）  │                     │
│                          │─ 保存 ─────────────▶│                     │
│                          │─ artifacts に INSERT│                     │
│◀─ 202 ───────────────────│                     │                     │
│                          │                     │                     │
│─ POST /finalize ────────▶│                     │                     │
│                          │─ status=FINALIZED（commit）                │
│                          │─ 判定 ────────────────────────────────────▶│─ 設定解決
│                          │                     │◀─ 読み出し ─────────│─ 正規化
│                          │                     │                     │─ 判定・保存
│◀─ 200 {status, verdict} ─│◀──────────────────────────────────────────│
```

**`finalize` は判定まで行い、結果を返す**（DD-15）。判定は保存済みの成果物を読んで DB に書くだけで、
外部 API を呼ばないため数秒で終わる。収集ランナーは応答の `status` / `verdict` をログに出し、
処理失敗（`FAILED`）ならワークフローを失敗にする。

確定（`FINALIZED`）は判定の前に別のトランザクションで確定させる。判定に失敗しても確定は取り消さず、
Run を `FAILED` として残す（理由は Run 詳細に表示される）。管理者は原因を直した後に再評価できる。
再評価（`POST /runs/{id}/reevaluate`）も同じ流れをその場で実行する。

### 3.2 判定の処理手順

```
1. 設定解決     提出された *.gate.yml を検証し、GateConfig 版を確定
2. 移動の解決   git-renames から比較元・比較対象 Run のコミットからのファイルの移動を求める（初回のみ。Run に保持）
3. 正規化       artifacts を 1 件ずつパースし、正規化モデルへ変換
4. 重複排除     指標ごとに定めたキーで Finding を名寄せ
5. 基準解決     比較対象 Run（比較元コミットの Run、無ければ同じブランチの直前の Run）を特定
6. 判定         指標ごとに MetricEvaluator を適用
7. 差分算出     比較対象 Run との fingerprint 比較で NEW / CONTINUING / RESOLVED を決定
8. 集約         Run 全体の verdict と completeness を決定
9. 保存         measurements / findings を置き換え、runs と読み取りモデルを更新
```

5〜9 は**ひとつのトランザクション**で行い、その間は Run の行をロックする（同じ Run の判定を重ねない）。
途中で失敗した場合、その Run に中途半端な判定結果が残らないようにするためである。
パース処理はトランザクションの外で先に済ませ、DB のトランザクションを長時間保持しない（3.3）。

### 3.3 トランザクション境界

| 処理 | 境界 |
| --- | --- |
| Run 作成 / 成果物受領 / 確定 | 1 API 呼び出し（確定は判定の前まで）= 1 トランザクション |
| 成果物のファイル保存 | **トランザクションの外**。先にファイルを保存し、成功後に DB へ INSERT する |
| 正規化（パース） | トランザクション外。結果をメモリ上の正規化モデルに保持 |
| 判定と保存 | 1 トランザクション。`measurements` / `findings` / `runs` / `repository_summaries` をまとめて確定 |
| 処理失敗の記録 | 判定のトランザクションとは別の短いトランザクション（判定側はロールバック済みのため） |

ファイル保存を先に行う順序にすると、DB に記録のない孤児ファイルが生まれうる。
これは日次バッチで「`artifacts` テーブルに存在しないファイル」を削除して回収する。
逆順（DB 先）にすると、参照先ファイルの無いレコードという
**より扱いにくい壊れ方**をするため、この順序を採る。

---

## 4. 判定と定期処理の実行方式

### 4.1 方式

判定は取り込みの確定（`finalize`）と再評価の要求の中で、その場で実行する（`RunEvaluationPipeline`）。
判定は呼び出したスレッドで同期的に行う。規模（1 日 10〜30 Run、送り手は収集ランナー 1 台）に対して、
この方式で足りる。判定は外部 API を呼ばないため、一時的な障害で再試行したい場面も無い。

### 4.2 失敗の扱い

| 失敗 | 扱い |
| --- | --- |
| 設定ファイルの検証エラー | Run を `FAILED`（`CONFIG_VALIDATION_FAILED`）とし、行番号つきの理由を記録する。設定版は保存しない |
| 成果物の形式不正 | Run は失敗させない。該当する指標を `ERROR` とし、理由を記録する（fail-closed） |
| 想定外の例外 | Run を `FAILED`（`EVALUATION_FAILED`）とし、例外のメッセージを記録する。ERROR ログを出す |

自動の再試行はしない。再実行しても同じ結果になる失敗（設定・形式の誤り）が大半で、
直した後に管理者が再評価すればよい。

### 4.3 日次バッチ

`@Scheduled` で直接動かす（`ScheduledMaintenance`）。時刻は `quality-gate.schedule.*` の cron 式で変えられる。

| 処理 | 時刻（既定） | 内容 |
| --- | --- | --- |
| 保持期間の削除 | 毎日 03:00 | 保持期間を過ぎた成果物のファイル・Run・孤児ファイル・監査ログの削除 |
| 滞留した Run の後始末 | 毎日 03:10 | 24 時間 `finalize` されない Run を `ABANDONED` に |

失敗しても翌日にもう一度動くため、その場では再試行しない（失敗は ERROR ログに出す）。
単一プロセス構成のため、多重実行の排他（ShedLock など）は要らない。

### 4.4 冪等性

| 対象 | 冪等性の担保 |
| --- | --- |
| Run 作成 | `(repository_id, commit_sha, attempt)` の一意制約。再送信は新しい attempt になる |
| 成果物アップロード | `(run_id, type, filename)` の一意制約。確定前の Run への同じ種別・同じファイル名の再送は**置き換え**になる（古い行を消してから新しい行を記録し、古いファイルはコミット後に消す）。ファイルの保存先は成果物ごとに分け（`<runId>/<成果物 ID>_<ファイル名>`）、再送や種別違いの同名ファイルが既存のファイルを上書きしないようにする |
| 確定 | 確定済みの Run への `finalize` は `409 RUN_ALREADY_FINALIZED`（二重に判定しない） |
| 再評価 | 同じ Run の判定は行ロックで直列化し、判定結果を置き換える |

---

## 5. 正規化モデル

### 5.1 アダプタのインタフェース

```java
public interface ArtifactAdapter {
    boolean supports(ArtifactType type);
    NormalizedReport parse(InputStream in, ParseContext context) throws ArtifactFormatException;
}
```

`ParseContext` は、パースに必要な周辺情報（対象コンポーネント、`exclusions`、
ベースコミット、計測メタデータ）を渡す。
アダプタは DB にもしきい値にもアクセスしない。**入力を読んで構造化するだけ**の責務に閉じる。

### 5.2 正規化モデル

```java
record NormalizedReport(
    ArtifactType       type,
    List<RawMeasurement> measurements,  // 指標の素の値（判定前）
    List<RawFinding>     findings,      // 個別の違反
    Map<String, String>  metadata       // ツール名・版、計測条件など
) {}

record RawMeasurement(
    String  metricId,      // "M-01" など
    String  componentName, // "backend" / "frontend" / null（全体）
    BigDecimal value,
    String  unit,          // "percent" / "ms" / "count" / "rps"
    Map<String, Object> detail   // 分母分子など、判定と表示に使う内訳
) {}

record RawFinding(
    String  metricId,
    String  ruleId,
    Severity severity,     // 正規化済み（CRITICAL / HIGH / MEDIUM / LOW / INFO）
    String  title,
    String  filePath,
    Integer line,
    Map<String, Object> detail
) {}
```

`fingerprint` はアダプタではなく `normalize` モジュールが付与する。
指標ごとの fingerprint 定義（[02](02-metrics-spec.md) 0.4）を
1 箇所に集約し、アダプタごとに実装がぶれないようにするためである。

### 5.3 アダプタ一覧

| アダプタ | `ArtifactType` | 供給する指標 |
| --- | --- | --- |
| `JacocoXmlAdapter` | `jacoco-xml` | M-01 |
| `LcovAdapter` | `lcov` | M-01 |
| `PitXmlAdapter` | `pit-xml` | M-02 |
| `K6SummaryAdapter` | `k6-summary` | M-03 / M-04 / M-05 |
| `SarifAdapter` | `sarif` | M-06 |
| `PmdXmlAdapter` | `pmd-xml` | M-07 |
| `JUnitXmlAdapter` | `test-junit-xml` | M-10 / M-11 |
| `OasdiffJsonAdapter` | `oasdiff-json` | M-08 |
| `AxeJsonAdapter` | `axe-json` | M-09 |
| `EslintJsonAdapter` | `eslint-json` | M-07 |

形式ごとの読み方は [02](02-metrics-spec.md) 0.5。
追加の指標（M-10〜M-13）のアダプタも [02](02-metrics-spec.md) 0.5 を参照。

`SarifAdapter` は M-06 だけを供給する。SARIF は複雑度も運びうるが、ツール名（`driver.name`）が
複雑度ツール（PMD / ESLint など）の run は読み飛ばし、M-06 の件数に複雑度違反を混ぜない。
ツール名の判定表は `SarifAdapter` の定数にまとめ、ツールの追加でロジックを変えずに済むようにしている。

---

## 6. 判定エンジン

### 6.1 評価器のインタフェース

```java
public interface MetricEvaluator {
    String metricId();
    // コンポーネントごとに複数の結果を返しうる（M-01 は backend / frontend を別に判定する）
    List<MetricResult> evaluate(EvaluationContext context);
}

record EvaluationContext(
    Run run,
    GateThresholds thresholds,             // 解決済みの設定（しきい値）
    NormalizedInput input,                 // 正規化済みの実測値と違反（head / base）
    Map<String, BigDecimal> previousValues, // 前回値。キーは指標 + コンポーネント + 計測条件
    boolean hasBaseline
) {}

record MetricResult(
    String  metricId,
    String  componentName,
    MeasurementStatus status,   // PASS / WARN / FAIL / SKIP / ERROR / NOT_APPLICABLE
    BigDecimal value,
    String  unit,
    Map<String, Object> threshold,
    String  reason,             // 判定理由（UI にそのまま出せる日本語）
    Map<String, Object> detail,
    List<IdentifiedFinding> findingsToPersist,
    String  variant             // 計測条件（M-02 の実行範囲、性能の計測環境）
) {}
```

指標ごとに 1 実装。指標の追加は `MetricEvaluator` の実装追加のみで完結する。
スキップ申告（6.2 の 2）の適用は評価器ではなく `RunEvaluationService` が行う。

### 6.2 判定の優先順位

各指標について、次の順に判定する。**先に該当したものが結果になる。**

```
1. config で enabled: false            → SKIP
2. スキップを申告                     → SKIP（skippable_metrics に含まれる場合）
   　　　　〃                          → ERROR（含まれない場合）
3. 成果物が未提出、または形式不正       → ERROR
4. しきい値に照らして判定               → PASS / WARN / FAIL
```

ツールの制約で測りようのないコンポーネント（M-02 の frontend など）は、上の手順とは別に `NOT_APPLICABLE` とする（[02](02-metrics-spec.md) M-02）。

### 6.3 差分算出（NEW / CONTINUING / RESOLVED）

```
現在の Run の fingerprint 集合 = C
比較対象 Run の fingerprint 集合 = B

NEW        = C \ B   今回の変更で発生した違反
CONTINUING = C ∩ B   前回から継続している違反
RESOLVED   = B \ C   今回の変更で解消された違反
```

`RESOLVED` の Finding も `findings` テーブルに状態付きで保存する。
差分をその場で計算する方式に比べてデータ量は増えるが、

- Run 詳細の表示が 1 回のクエリで済む
- Run が**不変のスナップショット**になり、後から比較対象 Run が削除されても表示が壊れない

という利点が大きい。対象 1 リポジトリの規模では容量が問題にならない。

比較対象 Run の決定:

| Run の種類 | 比較対象 |
| --- | --- |
| PR の Run | `baseCommitSha` に対応する最新の `EVALUATED` な Run。無ければベースブランチの最新 Run |
| ブランチ push の Run | 同一ブランチの直前の `EVALUATED` な Run |
| 初回 Run | 比較対象なし。すべて `NEW` 扱いとせず、`state = INITIAL` として区別する |

初回 Run で既存の違反をすべて「新規発生」と表示すると、
「この PR が 300 件の問題を持ち込んだ」という誤った印象を与える。
`INITIAL` はそれを避けるための状態である。

---

## 7. 設定の解決

### 7.1 取得元：収集ランナーが成果物として送る

設定は収集ランナー（[要件定義書](01-requirements.md) 3.1）が `quality-gate-config` 型の成果物として Run ごとに送る。
送るのは quality-gate リポジトリの `collector/targets/<owner>__<name>.gate.yml` で、設定の置き場所はここだけである（DD-13）。
変更はプルリクエストでレビューし、main にマージした後の計測から使われる。画面（S-06）は表示だけで、編集は受け付けない。
収集ランナーは計測したときの main のファイルを送るため、その Run の判定に使った内容が確実に残る。
バックエンドが GitHub から設定を取りに行く方式は採らない（GitHub の障害で判定が止まり、認証情報の管理も要るため）。

### 7.2 解決フロー

```
判定（RunEvaluationPipeline）
   │
   ├─ 成果物の中から quality-gate-config を探す
   │    │
   │    ├─ ある → スキーマ検証
   │    │      ├─ OK → 内容の SHA-256 を計算
   │    │      │        ├─ 同一ハッシュの GateConfig が既存 → その版を再利用
   │    │      │        └─ 無し → 新しい版として保存
   │    │      └─ NG → Run を FAILED（error_code = CONFIG_VALIDATION_FAILED）とし、
   │    │              行番号つきの検証エラーを理由に記録。設定版は保存しない
   │    │
   │    └─ 無い → その Run が前回の判定で使った版（Run の gate_config_id）。それも無ければシステム既定値
   │
   ▼
Run に gate_config_id を紐づける（判定の再現性）
```

検証エラーを判定結果 FAIL ではなく**処理失敗（FAILED）**として扱うのは、
不正な設定で判定を続けると意図しないしきい値で合格が出てしまうためである。
また、品質の問題（開発者が直す）と設定の誤り（設定を書いた人が直す）を
同じ見た目にしない（2.1 参照）。

**設定を Run に紐づける**ことが要点である。後からしきい値を変更しても、
過去の Run は「当時の設定でどう判定されたか」を保持し続ける。
再評価でも、その Run が送った設定（無ければ前回の判定で使った版）で判定し直す。新しい設定は次の計測から使われる。

### 7.3 優先順位

```
Run が送った設定ファイル > 前回の判定で使った版 > システム既定値
```

設定はファイルだけで管理する（Configuration as Code）。変更がレビューの対象になり、Git の履歴で追えるため。

### 7.4 検証

合格ライン（`*.gate.yml`）を検証する。検証エラーは**行番号とキーのパス付き**で記録し、
Run 詳細と設定の画面（S-06）で原因が分かるようにする。

| 規則 | 理由 |
| --- | --- |
| 未知のキーはエラー。編集距離の近い候補を添える | typo を黙って無視すると、設定したつもりの値が効かないまま合格が出続ける |
| 重複したキーはエラー | 後勝ちで黙らせると、消したはずの設定が効き続ける |
| 割合は 0〜100、件数は 0 以上 | 範囲外を通すと判定が意図せず緩くなる |
| 文字列で書かれた数値（`"75%"`）はエラー | 暗黙の変換をすると、書いた値と効く値がずれる |
| `skippable_metrics` の値は既知の指標名のみ | 綴りを間違えた指標はスキップが受理されず ERROR になる。原因が分かりにくい |

**判定対象は「設定で有効」かつ「判定器が実装済み」の積集合**とする。
未実装の指標まで判定対象に含めると、すべての Run が ERROR で不合格になる。

---

## 8. 認証・認可

### 8.1 ユーザー認証（GitHub OAuth）

```
ブラウザ          quality-gate            GitHub
   │                   │                    │
   │─ GET / ──────────▶│                    │
   │◀─ 302 /login ─────│                    │
   │─ GET /oauth2/authorization/github ────▶│（Spring Security が委譲）
   │                   │                    │
   │◀──────── 認可画面 ─────────────────────│
   │─ 許可 ────────────────────────────────▶│
   │◀─ 302 /login/oauth2/code/github?code= ─│
   │─ コールバック ───▶│                    │
   │                   │─ code → token ────▶│
   │                   │◀─ user info ───────│
   │                   │                    │
   │                   │  ★ 許可リスト照合（8.2）
   │                   │                    │
   │◀─ Set-Cookie: SESSION; HttpOnly; SameSite=Lax（HTTPS なら Secure）
   │◀─ 302 / ──────────│                    │
```

セッションは **Spring Session JDBC** で DB に保存する。
インメモリだとアプリ再起動のたびに全員がログアウトするため、
デプロイのたびに利用者を巻き込むことになる。

### 8.2 許可リストによる入口制御

Organization を使わないため、GitHub アカウントを持つ誰でも OAuth フロー自体は完了できる。
**認証の成功と、このシステムを使ってよいかは別**であり、後者を許可リストで判定する。

```java
// OAuth2 認証成功後のハンドラ（概念）
User user = users.findByGithubLogin(login)
    .orElseThrow(() -> new AccessDeniedException("許可リストに登録されていません"));
if (user.status() != ACTIVE) throw new AccessDeniedException("アカウントが無効です");
users.recordLogin(user, githubUserId, name, avatarUrl);
```

| 条件 | 挙動 |
| --- | --- |
| `users` に未登録 | 拒否。「管理者に登録を依頼してください」と表示する |
| `users` に登録済み・`ACTIVE` | ログイン成功。GitHub のユーザー ID を初回ログイン時に記録する |
| `users` に登録済み・`DISABLED` | 拒否 |
| **`users` が 1 件も無い（初期状態）** | 最初にログインしたユーザーを `ADMIN` / `ACTIVE` として自動登録する（FR-10-5） |

初期状態の自動登録は、システム構築直後に誰もログインできない状態を避けるためのもの。
**1 件でもユーザーが存在すれば二度と発動しない**条件にし、
不特定のユーザーが管理者になる経路を残さない。
この自動登録は監査ログに `BOOTSTRAP_ADMIN` として記録する。

### 8.3 Ingest Token 認証

参照系とは独立した認証経路を持つ（DD-20）。

| 項目 | 方式 |
| --- | --- |
| トークン | 収集ランナー用の 1 つ。バックエンドの環境変数 `QG_INGEST_TOKEN` と、収集ランナーの Secret `QG_INGEST_TOKEN` に同じ値を入れる |
| 交換 | `QG_INGEST_TOKEN` にカンマ区切りで新旧を並べると両方を受け付ける。収集ランナーを切り替えてから古い値を消す |
| 照合 | 送られた値と設定した値を、どちらも SHA-256 にしてから定数時間で比較する（長さや比較時間から推測させない） |
| スコープ | **書き込みのみ**（Run の作成・成果物の送信・確定）。参照 API は利用できない。登録していないリポジトリ、無効化したリポジトリの Run は作れない |
| 未設定 | 取り込み API はすべて 401 を返す |

送り手が収集ランナーだけのため、トークンはリポジトリごとに分けない。全リポジトリのトークンが同じ Secrets に並ぶ構成では、
分けても守りは強くならない。

### 8.4 認可

ロールは `ADMIN` / `VIEWER` の 2 種（[要件定義書 4.1](01-requirements.md)）。

| 層 | 実装 |
| --- | --- |
| API | Spring Security のメソッドセキュリティ（`@PreAuthorize("hasRole('ADMIN')")`） |
| 画面 | ルーティングガードで表示を制御 |

**画面側の制御は利便性のためのものであり、防御ではない。**
API 側の認可を唯一の権限境界とし、画面の制御が無くても権限は守られる状態にする。

---

## 9. エラー処理

### 9.1 API のエラー応答

RFC 9457（Problem Details）に従う。Spring の `ProblemDetail` を使う。

```json
{
  "type": "https://quality-gate.example/problems/artifact-format-invalid",
  "title": "成果物の形式が不正です",
  "status": 422,
  "detail": "jacoco-xml として解釈できませんでした: 行 12 で予期しない要素 <foo>",
  "instance": "/api/v1/runs/018f.../artifacts",
  "errorCode": "ARTIFACT_FORMAT_INVALID",
  "runId": "018f...",
  "artifactType": "jacoco-xml"
}
```

`errorCode` を機械可読な識別子として必ず含める。
`title` と `detail` は人間向けであり、文言の改善で変わりうるため、
収集ランナーのスクリプトがこれらに依存しないようにする。

### 9.2 収集ランナーのログで原因が分かること

取り込みの失敗は収集ランナーのログにしか残らない場合がある。
そのため `detail` には**何をどう直せばよいか**を書く。

| 悪い例 | 良い例 |
| --- | --- |
| `Invalid request` | `commitSha は 40 桁の 16 進数で指定してください（受信値: 6ab1e37）` |
| `File too large` | `ファイルサイズが上限 50MB を超えています（受信: 68MB）。JaCoCo のレポートは XML のみを送信してください` |

### 9.3 例外の分類

| 分類 | HTTP | リトライ | 例 |
| --- | --- | --- | --- |
| 入力エラー | 400 / 422 | しない | 形式不正、必須項目欠落、スキーマ検証エラー |
| 認証・認可 | 401 / 403 | しない | トークン不正、許可リスト未登録、権限不足 |
| 競合 | 409 | しない | 既に finalize 済みの Run への成果物追加 |
| 上限超過 | 413 | しない | ファイルサイズ超過 |
| 外部依存の一時障害 | 503 | する | DB の一時的な接続断 |
| 内部エラー | 500 | する | 想定外の例外 |

---

## 10. 可観測性

### 10.1 ログ

| 項目 | 方針 |
| --- | --- |
| 形式 | JSON 構造化ログ（Spring Boot の structured logging）。`QG_LOG_FORMAT` に `ecs` / `logstash` / `gelf` を指定する。`compose.yaml`（`full`）の既定は `ecs`。未指定なら開発向けのテキスト形式 |
| 相関 ID | `requestId`（全リクエスト。`X-Request-Id` ヘッダがあればそれを使い、応答にも返す）、`runId`（パスに runId を含む API と判定）を MDC に載せる。JSON 形式では項目として、テキスト形式では `req=` / `run=` として出る。エラー応答の `traceId` は `requestId` と同じ値 |
| 秘匿情報 | Ingest Token、セッション ID、GitHub のアクセストークンはログに出さない。マスク処理をログ出力の共通層に実装する |
| レベル | 判定結果は INFO。成果物の形式不正は WARN（システム異常ではないため）。判定の想定外の失敗と日次バッチの失敗は ERROR |

成果物の形式不正を ERROR にしないのは、**それが日常的に起こる正常系**だからである。
ERROR を「対応が必要な異常」に限定しておかないと、アラートが意味を失う。

### 10.2 メトリクス（Micrometer）

`/actuator/prometheus`（ADMIN のみ）で、Spring Boot が標準で出すメトリクス（JVM、HTTP、DB 接続プール）を公開する。

独自のメトリクスは持たない。収集する Prometheus もアラートの設定も無く、誰も読まない値になるためである。
監視の仕組みを用意するときに、必要なものだけを足す。

### 10.3 異常の気づき方

| 異常 | 気づき方 |
| --- | --- |
| 判定の処理失敗 | 収集ランナーの `submit` が失敗する。ERROR ログ（10.1）。Run は「処理失敗」として画面に出る |
| 日次バッチの未完了 | ログにバッチの完了が出ない |
| ストレージの逼迫 | サーバのディスク使用量を OS 側で監視する |

---

## 11. 性能設計の要点

| 要件 | 方式 |
| --- | --- |
| ダッシュボード p95 1.0 秒（NFR 10.1） | リポジトリごとの「最新 Run のサマリ」を専用の読み取りモデルとして保持し、判定完了時に更新する。表示時に Run を走査しない |
| トレンド API p95 800ms | `measurements` に `(repository_id, metric_id, measured_at)` の複合インデックスを張り、期間で範囲検索する（[06](06-database-design.md) 5 章） |
| 確定から判定結果の応答まで中央値 10 秒 | パースをトランザクション外に出し、DB への書き込みは一括 INSERT にする。判定は外部 API を呼ばない |
| 取り込みの同時実行 | 仮想スレッドで I/O 待ちを占有しない。収集ランナーは 1 台で直列に動くため、同時の取り込みはほぼ起きない |

読み取りモデルを別に持つ方式は、書き込み時に更新処理が増える。
それでも採るのは、**ダッシュボードが最も頻繁に開かれる画面**であり、
ここでの待ち時間が全利用者の体感を決めるためである。
書き込みは 1 日 10〜30 回、読み取りは毎日複数人が何度も行う。
