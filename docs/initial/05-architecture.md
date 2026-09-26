# quality-gate 方式設計

| 項目 | 内容 |
| --- | --- |
| ドキュメント名 | quality-gate 方式設計（基本設計） |
| バージョン | 1.0 |
| 最終更新 | 2026-09-23 |
| 前提文書 | [要件定義書 v1.1](01-requirements.md) / [指標・判定仕様](02-metrics-spec.md) / [技術スタック](04-tech-stack.md) |

本書は、処理の流れ・状態遷移・ジョブ実行・認証認可・エラー処理といった
**アプリケーション全体の動き方**を定める。
テーブル定義は [06](06-database-design.md)、API は [07](07-api-design.md)、
画面は [08](08-screen-design.md) を参照。

---

## 1. レイヤとモジュールの依存規則

```
com.qualitygate
├ ingest/       取り込み API、Ingest Token 認証、成果物の受領と保管
├ adapter/      ツール別パーサ（jacoco, lcov, pit, k6, sarif, pmd, junit, oasdiff, axe）
├ normalize/    正規化モデルへの変換、重複排除、fingerprint 生成
├ evaluate/     しきい値適用、指標判定、Run 集約、差分（新規 / 継続 / 解消）算出
├ config/       .quality-gate.yml の検証・版管理と、複数モジュールを組み立てる合成点（SecurityConfig など）
├ query/        参照系ユースケース（ダッシュボード・トレンド・一覧）
├ release/      リリース判定（UC-10）
├ admin/        管理系の操作 API（利用者・リポジトリ・トークン・監査ログ・保持期間・ジョブの再実行）
├ auth/         GitHub ログイン時の許可リスト照合、セッションのロール更新
├ job/          ジョブキューとスケジューラ
├ domain/       エンティティ・リポジトリ・正規化モデル・列挙値
└ platform/     監査ログ、ArtifactStore、共通例外、設定

バックエンドは GitHub API を呼ばない（ログインの OAuth だけ）。タグとファイルの移動は収集ランナーが求めて送る（D-26）。
```

### 依存規則

| 規則 | 理由 |
| --- | --- |
| `adapter` は `evaluate` を知らない | パーサは「ツールの出力を読む」だけの責務に留める。指標の判定基準が変わってもパーサは変わらない |
| `evaluate` は `adapter` を知らない | 判定は正規化モデルだけを入力とする。ツールを差し替えても判定ロジックは変わらない |
| `query` は書き込み系モジュール（`ingest` / `normalize` / `evaluate`）を呼ばない | 参照系は専用の読み取りモデルを持ち、書き込み側の都合に引きずられない |
| すべてのモジュールが `platform` に依存してよい。逆は不可 | 共通基盤が業務ロジックを知らない状態を保つ |
| 複数モジュールを組み立てる設定は `config` に置く | `SecurityConfig` は `auth` と `ingest` の両方を参照する。`platform` に置くと上の規則に違反する |
| `adapter` と `evaluate` は `config` を知らない | 判定とパースは解決済みの設定（`domain.gate`）と `ParseContext` だけを入力とする |
| `adapter` / `evaluate` / `query` / `ingest` は `github` に依存しない | 外部 API の障害の影響範囲を閉じ込める |

この依存規則は ArchUnit のテスト（`ModuleDependencyTest`）で機械的に検証する。
規則が文書にしか存在しないと、半年後には守られていないためである。

---

## 2. Run のライフサイクル

Run は「1 つのコミットに対する 1 回の計測・判定」を表す。**確定後は不変**とし、
再評価は新しい判定結果で上書きするのではなく、判定結果のみを差し替えて履歴に残す。

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
              │ FINALIZED │  取り込み完了。ジョブを登録して即座に応答
              └─────┬─────┘
                    │ ジョブ実行開始
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
| `FINALIZED` | CI が取り込み完了を宣言した | `PROCESSING` |
| `PROCESSING` | 正規化・判定を実行中 | `EVALUATED` / `FAILED` |
| `EVALUATED` | 判定完了。`verdict` が確定している | （再評価で `verdict` のみ更新） |
| `FAILED` | 正規化・判定が異常終了した | （手動で再実行可能） |
| `ABANDONED` | `finalize` されないまま 24 時間経過 | 終端 |

> **`FAILED` と `verdict = FAIL` は別物**である。前者は quality-gate 側の処理失敗、
> 後者は品質が合格ラインを満たさなかったという業務的な判定結果。
> UI でもこの 2 つを混同させない表示にする（[08](08-screen-design.md) 3.3）。

### 2.2 `ABANDONED` を設ける理由

CI が途中で落ちると `finalize` が呼ばれず、Run が `CREATED` / `UPLOADING` のまま残る。
これを放置すると、ダッシュボードの「最新 Run」が永久に処理中に見えてしまう。

日次バッチで 24 時間以上滞留した Run を `ABANDONED` とし、
ダッシュボードの最新 Run 判定からは除外しつつ、履歴には残す。
成果物は保持期間に従って削除される。

### 2.3 再試行（attempt）

同一コミットへの再送信は、既存 Run を上書きせず `attempt` を増やした新しい Run として記録する（FR-03-6）。
一意キーは `(repository_id, commit_sha, attempt)`。
ダッシュボードとトレンドは、同一コミットについて**最新 attempt のみ**を採用する。

---

## 3. 取り込みから判定までの流れ

### 3.1 シーケンス

```
CI                     Ingest API          ArtifactStore    Job Queue      Worker
│                          │                     │              │             │
│─ POST /runs ────────────▶│                     │              │             │
│                          │─ runs に INSERT     │              │             │
│◀─ 201 {runId} ───────────│                     │              │             │
│                          │                     │              │             │
│─ POST /artifacts (×N) ──▶│                     │              │             │
│                          │─ 検証（型/サイズ）  │              │             │
│                          │─ 保存 ─────────────▶│              │             │
│                          │─ artifacts に INSERT│              │             │
│◀─ 202 ───────────────────│                     │              │             │
│                          │                     │              │             │
│─ POST /finalize ────────▶│                     │              │             │
│                          │─ status=FINALIZED   │              │             │
│                          │─ ジョブ登録 ───────────────────────▶│             │
│◀─ 202 {status} ──────────│                     │              │             │
│  （CI はここで完了。判定を待たない）             │              │             │
│                          │                     │              │─ 取得 ─────▶│
│                          │                     │              │             │─ 設定解決
│                          │                     │◀─ 読み出し ────────────────│─ 正規化
│                          │                     │              │             │─ 判定
│                          │                     │              │             │─ 保存
```

**`finalize` は判定の完了を待たずに応答する。** 判定には数十秒かかりうるため、
同期にすると CI の待ち時間が延びる。quality-gate の障害が CI を止めないという
方針（NFR 10.3）とも整合する。

CI が判定結果を知りたい場合は `GET /runs/{runId}/status` をポーリングする。
ただし本フェーズではマージをブロックしないため、通常はポーリングしない。

### 3.2 判定ジョブの処理手順

```
1. 設定解決     提出された .quality-gate.yml を検証し、GateConfig 版を確定
2. 基準解決     baseCommitSha から比較対象（前回 Run / ベース Run）を特定
3. 正規化       artifacts を 1 件ずつパースし、正規化モデルへ変換
4. 重複排除     指標ごとに定めたキーで Finding を名寄せ
5. 判定         指標ごとに MetricEvaluator を適用
6. 差分算出     前回 Run との fingerprint 比較で NEW / CONTINUING / RESOLVED を決定
7. 集約         Run 全体の verdict と completeness を決定
8. 保存         measurements / findings を一括 INSERT、runs を更新
```

免除の適用（旧 5）と通知ジョブの登録（旧 10）は、免除と通知の廃止（D-22）で無くなった。

3〜8 は**ひとつのトランザクション**で行う。途中で失敗した場合、
その Run に中途半端な判定結果が残らないようにするためである。
ただしパース処理はトランザクションの外で先に済ませ、
DB のトランザクションを長時間保持しない（3.3）。

### 3.3 トランザクション境界

| 処理 | 境界 |
| --- | --- |
| Run 作成 / 成果物受領 | 1 API 呼び出し = 1 トランザクション |
| 成果物のファイル保存 | **トランザクションの外**。先にファイルを保存し、成功後に DB へ INSERT する |
| 正規化（パース） | トランザクション外。結果をメモリ上の正規化モデルに保持 |
| 判定と保存 | 1 トランザクション。`measurements` / `findings` / `runs` をまとめて確定 |
| ジョブの取り出しと結果記録 | それぞれ独立した短いトランザクション。**ジョブの実行自体は包まない**（4.5） |

ファイル保存を先に行う順序にすると、DB に記録のない孤児ファイルが生まれうる。
これは日次バッチで「`artifacts` テーブルに存在しないファイル」を削除して回収する。
逆順（DB 先）にすると、参照先ファイルの無いレコードという
**より扱いにくい壊れ方**をするため、この順序を採る。

---

## 4. ジョブ実行方式

### 4.1 方式

専用のメッセージキューは導入せず、**DB テーブルをキューとして使う**。
規模（1 日 10〜30 Run）に対して MQ の運用コストが見合わないためである。

```
jobs テーブル ──▶ @Scheduled(fixedDelay = 1s) のポーラ ──▶ ワーカー（仮想スレッド）
```

取得は `SELECT ... FOR UPDATE SKIP LOCKED` を使う。
単一プロセス構成では多重実行は起こらないが、**将来プロセスを増やしたときに
ここが壊れる**ため、最初から安全な取得方法にしておく。

### 4.2 ジョブ種別

| 種別 | 契機 | 内容 |
| --- | --- | --- |
| `EVALUATE_RUN` | `finalize` | 設定解決 → 正規化 → 判定（3.2） |
| `REEVALUATE_RUN` | 手動 | 保存済みの成果物と、その Run とともに送られた設定で判定し直す（判定のしくみを直したときなど） |
| `CLEANUP_RETENTION` | 毎日 03:00 | 保持期間超過の Run・成果物・孤児ファイルの削除 |
| `ABANDON_STALE_RUNS` | 毎日 03:10 | 24 時間滞留した未 finalize の Run を `ABANDONED` に |

`SEND_NOTIFICATION`（メール通知）・`DAILY_REEVALUATION`（日次再評価）・`EXPIRE_WAIVERS`（免除の期限切れ）・
`CHECK_FRESHNESS`（計測途絶の検知）は D-22 で廃止した。日次再評価は保存済みの成果物と Run の設定で判定し直すだけで、
新しい CVE やしきい値の変更は拾えず、判定を変えていたのは免除の増減だけだった。

### 4.3 リトライと失敗の扱い

| 項目 | 方針 |
| --- | --- |
| 最大試行回数 | 5 回 |
| バックオフ | 指数（1 分、2 分、4 分、8 分、16 分） |
| 恒久的失敗 | `status = DEAD` として保持。管理画面から一覧・手動再実行できる |
| リトライ対象 | ハンドラが一時的な障害として明示したもの（`RetryableJobException`。外部 API の一時エラーなど） |
| リトライ対象外 | 成果物の形式不正、設定ファイルの検証エラー（再実行しても同じ結果になる）と、想定外の例外。いずれも即座に `DEAD` にする |

**形式不正をリトライしない**のは重要である。リトライすれば直るものと、
入力そのものが誤っているものを区別せずに再試行すると、
失敗の原因が 5 回分のログに埋もれて見えなくなる。
後者は即座に Run を `EVALUATED`（該当指標は `ERROR`）として確定させ、
CI 側に結果を返す。

### 4.4 ジョブの実行をトランザクションで包まない

ポーラがジョブの取り出しから実行・結果記録までを 1 つのトランザクションで包むと、
次の 2 つの問題が起きる。

| 問題 | 影響 |
| --- | --- |
| パースやファイル読み取りの間、DB のトランザクションを保持し続ける | 接続を長時間占有する |
| ハンドラ内の `@Transactional` が例外でロールバック専用になると、外側のコミットが `UnexpectedRollbackException` で失敗する | **ジョブの状態更新まで巻き戻り、同じジョブが毎秒再実行され続ける** |

2 番目が深刻である。ジョブは PENDING・試行回数 0 のまま残るため、
リトライ上限にもバックオフにも引っかからない。**失敗の記録が残らないまま
1 秒間隔で永久に実行される**（毒入りジョブ）。設定の検証エラーで実際に起きた。

そのため次の構造にする。

```
poll()                          ← トランザクションなし
  ├─ queue.claim(4)             ← トランザクション: FOR UPDATE SKIP LOCKED で
  │                                RUNNING に更新して commit
  ├─ handler.handle(job)        ← トランザクションなし（ハンドラが自分で張る）
  └─ queue.markSucceeded/Failed ← トランザクション: 結果だけを記録
```

`claim` が commit した時点でジョブは RUNNING になり、他のポーラからは見えなくなる。

ただしこの構造では、プロセスが異常終了すると RUNNING のまま残るジョブが生まれる。
`claim` の先頭で **15 分以上 RUNNING のジョブを実行待ちに戻す**（試行回数は増やさない）。
これが無いと、異常終了したジョブは誰にも処理されず永久に残る。

### 4.5 冪等性

| 対象 | 冪等性の担保 |
| --- | --- |
| Run 作成 | `(repository_id, commit_sha, attempt)` の一意制約。CI のリトライで重複 Run を作らない |
| 成果物アップロード | `(run_id, type, filename)` の一意制約。確定前の Run への同じ種別・同じファイル名の再送は**置き換え**になる（古い行を消してから新しい行を記録し、古いファイルはコミット後に消す）。ファイルの保存先は成果物ごとに分け（`<runId>/<成果物 ID>_<ファイル名>`）、再送や種別違いの同名ファイルが既存のファイルを上書きしないようにする |
| ジョブ登録 | `(type, dedup_key)` の一意制約。`EVALUATE_RUN` の `dedup_key` は `runId` |

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
| `JUnitXmlAdapter` | `test-junit-xml` | M-11 / M-12 |
| `OasdiffJsonAdapter` | `oasdiff-json` | M-09 |
| `AxeJsonAdapter` | `axe-json` | M-10 |
| `EslintJsonAdapter` | `eslint-json` | M-07 |

形式ごとの読み方は [02](02-metrics-spec.md) 0.5。
収集ランナーが送らない形式（istanbul-json / gatling-log / osv-json / lizard-csv / pact-verification）のアダプタは D-19 で削除した。
その後に追加した指標（M-11〜M-17）のアダプタは [02](02-metrics-spec.md) 0.5 を参照。

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
    MeasurementStatus status,   // PASS / WARN / FAIL / SKIP / REFERENCE / ERROR / NOT_APPLICABLE
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
2. CI がスキップを申告                 → SKIP（skippable_metrics に含まれる場合）
   　　　　〃                          → ERROR（含まれない場合）
3. 成果物が未提出、または形式不正       → ERROR
4. しきい値に照らして判定               → PASS / WARN / FAIL
```

計測条件が統制外の性能値を `REFERENCE` にする段（旧 4。GitHub ホストランナーでの計測）は、
計測を専有のセルフホストランナーで動く収集ランナーに絞ったため削除した（D-19）。

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

設定は収集ランナー（[要件定義書](01-requirements.md) 3.2 / D-16）が `quality-gate-config` 型の成果物として Run ごとに送る。
送るのは quality-gate リポジトリの `collector/targets/<owner>__<name>.gate.yml` で、設定の置き場所はここだけである（D-20）。
変更はプルリクエストでレビューし、main にマージした後の計測から使われる。画面（S-06）は表示だけで、編集は受け付けない。
当初は収集ランナーが設定を送らず画面で保存した設定で判定していたが、画面と控えのファイルの 2 か所に分かれるため改めた。
quality-gate 自身が GitHub Contents API で取得する方式も検討したが、採らなかった。

| 観点 | 判断 |
| --- | --- |
| 改竄への耐性 | **差は生まれない。** 取り込み型（D-1）では計測値そのものを CI が送っており、既に CI を信頼している。設定だけ別経路で取っても守れる範囲は増えない |
| 障害点 | GitHub API の障害が判定を止めなくなる |
| 認証情報 | GitHub App の秘密鍵とインストールトークンの管理が不要になる |
| 送った内容との一致 | 収集ランナーは計測したときの main のファイルを送るため、その Run の判定に使った内容が確実に残る |

`gate_configs.source_type` の `UI` と `source_commit_sha` は、D-20 より前の版（画面で保存した版と、対象の CI が送った版）にだけ入っている。

### 7.2 解決フロー

```
判定ジョブ
   │
   ├─ 成果物の中から quality-gate-config を探す
   │    │
   │    ├─ ある → スキーマ検証
   │    │      ├─ OK → 内容の SHA-256 を計算
   │    │      │        ├─ 同一ハッシュの GateConfig が既存 → その版を再利用
   │    │      │        └─ 無し → 新しい版として保存
   │    │      └─ NG → Run を FAILED（error_code = CONFIG_VALIDATION_FAILED）とし、
   │    │              行番号つきの検証エラーを理由に記録。ジョブは再試行しない
   │    │
   │    └─ 無い → 前回の判定で使った版（Run の gate_config_id。D-20 より前の Run）。それも無ければシステム既定値
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
画面からの編集（旧 UI 設定）は、ファイルと画面の 2 か所に合格ラインが分かれるため D-20 で削除した。

### 7.4 検証

`.quality-gate.yml` を検証する。検証エラーは**行番号とキーのパス付き**で返し、
UI と CI ログの双方で原因が分かるようにする。

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
| **`users` が 1 件も無い（初期状態）** | 最初にログインしたユーザーを `ADMIN` / `ACTIVE` として自動登録する（FR-13-5） |

初期状態の自動登録は、システム構築直後に誰もログインできない状態を避けるためのもの。
**1 件でもユーザーが存在すれば二度と発動しない**条件にし、
不特定のユーザーが管理者になる経路を残さない。
この自動登録は監査ログに `BOOTSTRAP_ADMIN` として記録する。

### 8.3 Ingest Token 認証

参照系とは独立した認証経路を持つ。

| 項目 | 方式 |
| --- | --- |
| 形式 | `qg_<8文字のprefix>_<32文字のランダム>` |
| 保管 | SHA-256 のハッシュのみ保存。平文は発行時に 1 度だけ表示 |
| 照合 | prefix でレコードを引き、ハッシュを定数時間比較する |
| スコープ | 発行元リポジトリへの**書き込みのみ**。参照 API は利用できない |
| 失効 | `revoked_at` を設定。即座に無効化される |
| 記録 | `last_used_at` を更新し、未使用トークンを管理画面で検出できるようにする |

prefix を分離するのは、ハッシュだけではトークンからレコードを引けず、
全件走査が必要になるため。prefix でインデックスを効かせたうえで、
**秘密部分は定数時間比較**する（比較時間からの推測を防ぐ）。

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
CI 側のスクリプトがこれらに依存しないようにする。

### 9.2 CI 側から原因が分かること

取り込みの失敗は CI のログにしか残らない場合がある。
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
| 相関 ID | `requestId`（全リクエスト。`X-Request-Id` ヘッダがあればそれを使い、応答にも返す）、`runId`（パスに runId を含む API と、判定などのジョブ）、`jobId`（ジョブ）を MDC に載せる。JSON 形式では項目として、テキスト形式では `req=` / `run=` / `job=` として出る。エラー応答の `traceId` は `requestId` と同じ値 |
| 秘匿情報 | Ingest Token、セッション ID、GitHub のアクセストークンはログに出さない。マスク処理をログ出力の共通層に実装する |
| レベル | 判定結果は INFO。成果物の形式不正は WARN（システム異常ではないため）。ジョブの恒久的失敗は ERROR |

成果物の形式不正を ERROR にしないのは、**それが日常的に起こる正常系**だからである。
ERROR を「対応が必要な異常」に限定しておかないと、アラートが意味を失う。

### 10.2 メトリクス（Micrometer）

`/actuator/prometheus`（ADMIN のみ）で、Spring Boot が標準で出すメトリクス（JVM、HTTP、DB 接続プール）を公開する。

独自のメトリクス（`qg.ingest.*`、`qg.evaluation.duration`、`qg.jobs.*`、`qg.artifacts.bytes`、`qg.notifications`、
`qg.rate_limit.rejected`）は削除した。収集する Prometheus もアラートの設定も無く、誰も読まない値を記録し続けていたためである。
監視の仕組みを用意するときに、必要なものだけを足す。

### 10.3 異常の気づき方

| 異常 | 気づき方 |
| --- | --- |
| ジョブの恒久的失敗 | ERROR ログ（10.1）。Run は「処理失敗」として画面に出る |
| ワーカーの停止・滞留 | Run が「処理中」のまま進まない（画面） |
| 日次バッチの未完了 | ログにバッチの完了が出ない |
| ストレージの逼迫 | サーバのディスク使用量を OS 側で監視する |

---

## 11. 性能設計の要点

| 要件 | 方式 |
| --- | --- |
| ダッシュボード p95 1.0 秒（NFR 10.1） | リポジトリごとの「最新 Run のサマリ」を専用の読み取りモデルとして保持し、判定完了時に更新する。表示時に Run を走査しない |
| トレンド API p95 800ms | `measurements` に `(repository_id, metric_id, measured_at)` の複合インデックスを張り、期間で範囲検索する（[06](06-database-design.md) 5 章） |
| 判定完了まで中央値 60 秒 | パースをトランザクション外に出し、DB への書き込みは一括 INSERT にする |
| 同時取り込み 10 Run | 仮想スレッドで I/O 待ちを占有しない。ワーカーは 1 回のポーリングで最大 4 件を取り出し、順に処理する（件数は定数） |

読み取りモデルを別に持つ方式は、書き込み時に更新処理が増える。
それでも採るのは、**ダッシュボードが最も頻繁に開かれる画面**であり、
ここでの待ち時間が全利用者の体感を決めるためである。
書き込みは 1 日 10〜30 回、読み取りは毎日複数人が何度も行う。
