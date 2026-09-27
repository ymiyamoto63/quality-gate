# アーキテクチャ

quality-gate の構成、測定の仕組み、バックエンドの構造、データベース、API と画面の共通規則をまとめる。
機能ごとの要件と設計は [features/](../README.md#機能ごとの要件と設計) に、指標の定義は [指標](metrics.md) にある。

---

## 1. 全体像

```
 対象リポジトリ（private）──clone（GitHub App の読み取り専用トークン）──┐
                                                                        ▼
 quality-gate リポジトリの GitHub Actions（collect.yml。専有のセルフホストランナー）
   fetch   取得し、比較元とタグを求める
   measure 計測する（コンテナの中。認証情報を渡さない）
   submit  Ingest API へ送る（合格ラインも送る）
                                                                        │ HTTPS / Ingest Token
                                                                        ▼
 quality-gate アプリ（Spring Boot 1 プロセス）
   取り込み → 正規化 → 判定 → 保存 ──▶ PostgreSQL 17 / 成果物ストア（ローカル FS）
   画面（Vue 3 SPA を同梱）・参照 API  ◀── ブラウザ（GitHub でログイン）
```

| 要素 | 役割 |
| --- | --- |
| 収集ランナー | 対象を取得して計測し、成果物を Ingest API に送る。計測方法・ツールの版・合格ラインは quality-gate リポジトリの `collector/` に置き、**対象リポジトリには何も置かない** |
| quality-gate アプリ | 送られた成果物を取り込み、合格ラインで判定して保存する。**テストやスキャンは実行しない**。判定結果・推移・リリース判定を画面で見せる |
| PostgreSQL | Run・指標値・違反・利用者・監査ログ・セッション |
| 成果物ストア | 取り込んだ元の成果物（`QG_ARTIFACT_ROOT`） |

- 実行に必要なプロセスは **PostgreSQL と Spring Boot の 2 つだけ**。判定は取り込みの確定の中でその場で行い、日次バッチは `@Scheduled` で動くため、キューやキャッシュは持たない
- SPA は Spring Boot に同梱して**同一オリジン**で配信する。CORS が要らず、認証を HttpOnly のセッション Cookie で完結できる（ブラウザに認証トークンを置かない）
- バックエンドは GitHub API を呼ばない（ログインの OAuth だけ）。比較元とタグは収集ランナーが clone した履歴から求めて送る

---

## 2. 設計判断

構成を決めている判断とその理由。番号（DD-xx）はコードのコメントからも参照している。

### 基本方針

| # | 項目 | 判断 | 理由 |
| --- | --- | --- | --- |
| DD-1 | 利用形態 | **社内専用・単一テナント**。インターネットには公開しない | 対象は社内のリポジトリで、利用者も社内の少人数に限られる |
| DD-2 | 対象リポジトリの構成 | **モノレポ**（`backend/` + `frontend/`）を 1 リポジトリ単位で扱う。複数のリポジトリを束ねる上位概念は持たない | backend と frontend の結果が 1 つの Run にまとまり、両者にまたがる指標（M-07 など）を扱いやすい |
| DD-3 | 判定結果の扱い | **可視化に徹し、PR のマージをブロックしない** | 合格ラインの妥当性を実績で確かめる前にブロックすると、しきい値の一時緩和やチェックの回避が常態化し、ゲートが形骸化する |
| DD-4 | 技術スタック | **Spring Boot 4（Java 25・Maven）+ Vue 3**。SPA を同梱して同一オリジンで配信し、API の型は OpenAPI から生成する。成果物はローカルファイルシステムに置く | CORS が不要で、認証をセッション Cookie で完結できる。この規模ではオブジェクトストレージは過剰（[開発環境](development.md#7-技術スタック)） |
| DD-5 | quality-gate 自身の品質 | **quality-gate 自身は計測対象にせず、PR の CI（`ci.yml`）で確かめる**。単体・結合テスト、フロントエンドの静的検査、生成物の同期、アクセシビリティ（axe-core の critical / serious 0 件）、脆弱性（修正版のある重大・高 0 件）に失敗したらマージしない | 少人数で使う社内ツールで、性能指標（50 req/s の負荷試験）は実際の使われ方とかけ離れている。セルフホストランナーは収集ランナー専用で、自身の計測で占有すると性能計測の条件が崩れる |
| DD-23 | 画面を持つか | **Web アプリ（バックエンド + DB + 画面）とする** | 推移の表示、開発に詳しくない人も読めるリリース判定、CSV の証跡が要る。判定だけなら収集ランナーの出力で足りるが、履歴を持てない |

### 計測

| # | 項目 | 判断 | 理由 |
| --- | --- | --- | --- |
| DD-6 | 計測と判定の分担 | **収集ランナーが対象を取得して計測し、Ingest API に送る。バックエンドは取り込みと判定だけを行う**。対象リポジトリには設定・ワークフロー・計測用の依存を置かない。Ingest API の送り手は収集ランナーだけ | バックエンドに Docker・ビルドツール・対象の認証情報を持ち込まずに済む。計測方法と合格ラインを一元管理でき、対象のチームに保守を求めない。代わりに、対象のビルド構成が変わったときの追従は quality-gate の運用者が担う |
| DD-7 | 計測のきっかけ | **収集ランナーの手動実行**（対象とブランチ・コミット・タグ・PR を指定する） | 計測したい時点（リリース前・PR の確認など）は人が決める |
| DD-8 | PR の計測とスキップ | **PR の計測では PIT と負荷試験を実行せず、スキップを申告する**。申告できるのは合格ラインの `execution.skippable_metrics` の指標だけで、それ以外の申告と申告の無い未提出は `ERROR` | 時間のかかる計測で PR の確認を待たせない。自由にスキップできると fail-closed が骨抜きになるため、申告制と許容リストで両立させる |
| DD-9 | コンポーネント | **計測プロファイル（`BACKEND_DIR` / `FRONTEND_DIR`）だけで決める**。判定と表示は成果物に付いたコンポーネント名を使う | 定義を他に持つとずれる |
| DD-10 | GitHub 連携 | **バックエンドは GitHub API を呼ばない**。比較元とタグは収集ランナーが `git merge-base` / `git tag --points-at` で求めて送る | バックエンドに App の秘密鍵を置かずに済み、GitHub の障害で判定が止まらない。代わりに、タグを付ける前に計測したコミットはタグで引けない |
| DD-11 | 性能計測の条件 | **到達率 50 req/s を負荷条件として固定し、その下で p95 と エラー率を判定する**。到達率は指標にせず M-03 の前提として確かめる。計測は他のジョブと同居しない専有のセルフホストランナーで行う | スループットを結果として測ると合否が負荷のかけ方で変わる。共有の実行環境ではノイズで値が揺れ、絶対値で判定できない |
| DD-12 | ミューテーションテスト | **PIT を使い、backend だけを対象にする**。frontend は `NOT_APPLICABLE` とし部分計測の理由にしない | PIT は JVM 専用。業務ロジックはバックエンドに寄っている |
| DD-17 | リリース判定のための計測 | **タグを指定して計測でき、比較元は前のタグにする。PIT と負荷試験は PR 以外のすべての計測で実行する**。比較対象 Run は比較元コミットの Run を優先する | リリースのタグを完全計測にし、前回のリリースからの変更全体で新規の違反を数える。手動の計測は順不同のため、直前に計測した Run がリリースより新しいことがある |
| DD-22 | 対象の登録 | **計測プロファイル（`collector/targets/<owner>__<name>.env`）を置くことが登録を兼ねる**。バックエンドは初めて計測が届いたリポジトリを登録し、既定ブランチは計測ごとに送られる値で更新する。画面での登録・無効化は持たない | 対象の情報を 2 か所に持つとずれる。送り手は Ingest Token を持つ収集ランナーだけのため、登録の手順を挟んでも守りは強くならない |

### 判定

| # | 項目 | 判断 | 理由 |
| --- | --- | --- | --- |
| DD-13 | 合格ラインの置き場所 | **`collector/targets/<owner>__<name>.gate.yml` だけ**。収集ランナーが Run ごとに送り、その内容で判定する。送られた設定は Run の成果物として Run と同じ期間残し、送った quality-gate のコミットを Run に記録する。DB で版を管理せず、画面は表示だけ | 判定に使った合格ラインと管理している合格ラインがずれない。版と変更理由は Git の履歴が持つ |
| DD-14 | しきい値の適用 | **絶対値のしきい値を当初から適用する**（ラチェット方式は採らない） | 対象はベースラインが整っており、マージもブロックしない |
| DD-15 | 判定の実行方式 | **取り込みの確定（`finalize`）と再評価の中で、その場で判定して結果を返す**。失敗した Run は `FAILED` として残し、管理者が再評価で直す | 判定は保存済みの成果物を読んで DB に書くだけで数秒で終わる。収集ランナーは応答で結果を知れ、単一プロセスのまま運用できる |
| DD-16 | リリース判定 | **タグかコミットを指定し、そのコミットで判定済みの Run から結論を出す**。同じコミットの最新の完全計測を使い、近くのコミットで代用しない。判定し直さない。根拠の種類（外部基準 / 業界の目安 / チーム判断）を示す | 近くのコミットの結果は別のコードの結果。見るたびに結論が変わると証跡にならない。チームで決めた値を外部の基準と同じ口調で書かない |
| DD-21 | M-06 の数え方 | **計測したコミットの、しきい値を超える関数の件数（絶対値）で判定する**。比較元との差分は取らない | リリース判定で見たいのは今のコードの状態。差分を取るには比較元の解析とファイルの移動の追跡が要る |

### 認証と認可

| # | 項目 | 判断 | 理由 |
| --- | --- | --- | --- |
| DD-18 | ログイン | **GitHub App の user-to-server 認可フロー**でログインし、ログインの可否は quality-gate 内の**許可リスト**で決める | Organization を持たない（GitHub Free の個人アカウント）ため、組織のメンバーシップを条件にできない |
| DD-19 | ロール | **Admin / Viewer の 2 つ** | 対象 1〜数リポジトリ・利用者数名の規模ではこれで足りる |
| DD-20 | Ingest Token | **収集ランナー用の 1 つを、バックエンドの環境変数と収集ランナーの Actions Secret に置く**。交換は新旧をカンマ区切りで並べて行う | 送り手は収集ランナーだけ。対象ごとに分けても守りは強くならない |

---

## 3. 測定の仕組み（収集ランナー）

手順は [運用](operations.md) にある。ここでは構成と考え方を記す。

### 3.1 要件

| ID | 要件 |
| --- | --- |
| COL-1 | 対象リポジトリを変更せずに、指定したブランチ・コミット・タグ・PR を取得・計測して Ingest API に送る。PIT と負荷試験は PR 以外の計測で実行する |
| COL-2 | 「バックエンド」「フロントエンド」のコンポーネント単位で指標を扱う。コンポーネントは計測プロファイル（`BACKEND_DIR` / `FRONTEND_DIR`）で決まる |

計測できる対象は **Maven（JUnit 5）の backend と npm + Vitest の frontend のモノレポ**に限る。構成が決まっているため、ビルド方法の自動検出を作り込まない。

### 3.2 ランナーとは

GitHub Actions のワークフローを実際に実行するコンピューターをランナーという。収集ランナーは**自分で用意したマシン（セルフホストランナー）**で動く。

| 理由 | 説明 |
| --- | --- |
| quality-gate に届く | 社内や手元で動かしている quality-gate には、GitHub ホストランナーからは届かない |
| 性能計測の条件をそろえる | 性能は毎回同じ専有のマシンで測らないと比べられない（DD-11） |
| 無料枠を使わない | 計測は時間がかかる |

ランナーは GitHub に「仕事はあるか」と**外向きに**取りに行く。マシンに外から接続できる必要はない。
ランナーは 1 台で、ジョブは 1 つずつ実行される（性能計測が他のジョブと重ならない）。止まっている間のジョブは待機し、24 時間で取り消される。

### 3.3 構成とジョブ

| ファイル | 役割 |
| --- | --- |
| `.github/workflows/collect.yml` | 手動実行（`workflow_dispatch`）で対象とコミットを受け取り、`fetch` → `measure` → `submit` の 3 ジョブで計測する |
| `collector/bin/fetch.sh` | 対象を全履歴ごと clone し、計測するコミット・比較元・タグを決めて `meta.env` に書く |
| `collector/bin/measure-isolated.sh` | 計測用のコンテナを用意し（無ければビルド）、その中で `measure.sh` を実行する |
| `collector/bin/measure.sh` | 計測の準備と順序。指標ごとの計測は `collector/bin/measure/*.sh` |
| `collector/bin/submit.sh` | Run の作成 → 成果物と合格ラインのアップロード → 確定 |
| `collector/bin/lib.sh` | 計測プロファイルの読み込みなどの共通関数 |
| `collector/versions.env` | ツールの版（JaCoCo・PMD・PIT・oasdiff・Trivy・yq・Maven・k6） |
| `collector/runner/Dockerfile` | 計測用のコンテナ（JDK・Node.js・Maven・Trivy・oasdiff・yq・Playwright と Chromium・ESLint） |
| `collector/a11y/` / `collector/complexity/` / `collector/pit/` / `collector/pmd-ruleset.xml` | M-08 の検査スクリプト、M-06（frontend）の ESLint の設定、PIT の取得用 pom、M-06（backend）のルールセット |
| `collector/targets/<owner>__<name>.env` | 計測プロファイル（**どう測るか**）。置くことが対象の登録を兼ねる |
| `collector/targets/<owner>__<name>.gate.yml` | 合格ライン（**何を合格とするか**）。無ければ計測・送信の前に止まる |
| `collector/targets/<owner>__<name>.k6.js` | 負荷試験のシナリオ（性能を測る場合） |

| ジョブ | やること | 認証情報 | 所要時間 |
| --- | --- | --- | --- |
| `fetch` | GitHub App から対象だけを読める 1 時間有効のトークンを発行し、clone する。トークンは `.git/config` に残さない | App の秘密鍵 → 読み取り用トークン | 1 分未満 |
| `measure` | 計測用のコンテナの中でビルド・テスト・解析をし、成果物を `reports/` にまとめる。最後に作業領域を消す | **なし** | 数分（PR 以外で負荷試験を含めると 20 分以上） |
| `submit` | `reports/` と合格ラインを Ingest API に送り、確定する。処理失敗（`FAILED`）ならジョブを失敗にする。判定結果の不合格ではジョブを失敗にしない | Ingest Token | 1 分未満 |

ジョブ間の受け渡しは Actions の成果物で行う（取得したソース `collector-source` は 1 日、計測結果 `collector-reports` は 7 日で消える）。

### 3.4 比較元とタグ

比較元（`baseCommitSha`）は `fetch.sh` が次の順に決める。

1. `commit` に**タグ**を指定し、既定ブランチ上の計測なら、その前のタグ（`git describe --tags`）。前のタグが無ければ直前のコミット
2. PR でなく、既定ブランチ上の計測なら、直前のコミット
3. それ以外（PR や別のブランチ）は既定ブランチとの merge-base

あわせて、計測するコミットを指すタグ（`git tag --points-at`）を Run の `tags` として送る。リリース判定でタグをコミットに解決するのに使う。

### 3.5 何を計測するか

- **何を測るかは合格ラインの `metrics.<指標>.enabled` だけで決める**。`measure.sh` は計測の前に合格ラインを読み（`yq`）、`enabled: false` の指標は計測しない。計測プロファイルは「どう測るか」だけを持つ
- テスト（M-01 / M-09 / M-10）はビルドを兼ね、M-02・M-03 / M-04・M-08 が使う jar も作るため、常に実行する
- PR の計測では M-02 と M-03 / M-04 を実行せず、`reports/skipped-metrics.tsv` に理由を書く。`submit.sh` がこれを `skippedMetrics` として申告する
- 計測プロファイルの `SKIP_METRICS` に書いた指標は常にスキップを申告する
- テストが失敗しても計測は止めない（失敗は M-09 の材料）。成果物が出なかった指標は送られず、quality-gate では ERROR になる

指標ごとの計測方法は [指標](metrics.md) の各節にある。

### 3.6 計測のコンテナ隔離

収集ランナーは**対象のビルド・テストのコードを実行する**。依存関係まで含めると信頼しきれないため、`measure` ジョブは計測をコンテナに閉じ込める。

| 項目 | 内容 |
| --- | --- |
| 見せるもの | 作業ディレクトリと `reports/`（読み書き）、`collector/`（読み取りのみ）、キャッシュ用のボリューム `quality-gate-collector-home`（Maven・npm・PMD・k6・Trivy の DB） |
| 見せないもの | ランナーのマシンの他のファイル、Docker のソケット、他のジョブの作業領域、認証情報（`measure` ジョブにそもそも無い） |
| 権限 | ランナーの利用者の UID で動かし、`--cap-drop ALL` と `no-new-privileges`。メモリ（既定 6 GB）とプロセス数に上限を付ける |
| 通信 | 外向きの通信はできる（依存関係の取得のため）。起動した対象アプリのポートはコンテナの中に閉じる |
| イメージ | JDK は計測プロファイルの `JAVA_VERSION`、Node.js は対象の `.nvmrc` の版で初回にビルドする。ベースのイメージ・Dockerfile・ツールの lock が同じなら作り直さない |

`measure.sh` をコンテナの外で直接実行すると、ツールのディレクトリが無いため止まる。

隔離は「対象のコードからランナーのマシンを守る」ためのもので、外への通信は制限しない。**自分たちが管理するリポジトリだけを対象にする**。

### 3.7 認証情報と安全対策

| 鍵 | 使う場所 | 置き場所 | できること |
| --- | --- | --- | --- |
| GitHub App の秘密鍵 → インストールトークン | `fetch` | quality-gate の Actions Secrets | 対象リポジトリを読む（1 時間。書き込み不可）。App はログイン用と同じもので、Contents / Pull requests の Read-only を付けて対象にインストールする |
| Ingest Token | `submit` | quality-gate の Actions Secrets と、アプリの環境変数（同じ値） | Run を作って成果物を送る（参照 API は呼べない） |
| ログイン用の Client ID / Secret | アプリ | アプリの環境変数（`.env`） | 利用者のログイン |

| リスク | 対策 |
| --- | --- |
| 対象のテストコードが同じジョブのシークレットを読む | **ジョブを 3 つに分け**、対象のコードを動かす `measure` に認証情報を渡さない |
| 前回の計測の残骸が次回に影響する / ソースがランナーに残る | 作業領域はジョブの最後に必ず消す |
| 対象のコードがランナーのマシンに触れる | 計測をコンテナに隔離する（3.6） |
| private のソースやテスト出力がログから漏れる | quality-gate リポジトリを private に保つ |
| フォークからの PR で任意のコードが動く | 計測するのは同一リポジトリ内のブランチからの PR だけにする |
| 性能計測と他のジョブが重なって値が乱れる | ランナーを専有のマシンに 1 台だけ置き、他のランナーや常駐サービスを同居させない |

デプロイキーや個人アクセストークンは、対象ごとの管理や個人への依存が生じるため使わない。

### 3.8 採らなかった方式

| 方式 | 採らなかった理由 |
| --- | --- |
| バックエンドの中で Docker を使って計測する | バックエンドのホストに Docker ソケット（実質 root）を渡し、DB と同じ場所で対象のコードを動かすことになる |
| ランナー機に常駐する収集デーモン | 監視・再起動・ログの仕組みを自前で持つ必要があり、この規模には過剰 |
| 対象リポジトリの CI から送る | 対象ごとにワークフローと設定の保守が要り、計測条件もそろわない |

---

## 4. バックエンド

### 4.1 モジュールと依存規則

```
com.qualitygate
├ ingest/       取り込み API、Ingest Token 認証、成果物の受領と保管、リポジトリの登録
├ pipeline/     合格ラインの解決 → 正規化 → 判定の流れ（確定と再評価から呼ぶ）
├ adapter/      ツール別パーサ（jacoco, lcov, pit, k6, sarif, pmd, eslint, junit, oasdiff, axe）
├ normalize/    正規化、fingerprint の生成
├ evaluate/     しきい値の適用、指標の判定、Run の集約、違反の新規 / 継続 / 解消
├ config/       合格ラインの検証と解決、複数モジュールを組み立てる設定（SecurityConfig など）
├ query/        参照系（ダッシュボード・Run・違反・トレンド・成果物）
├ release/      リリース判定と CSV
├ admin/        管理系の操作（利用者・再評価・監査ログ）
├ auth/         GitHub ログイン時の許可リスト照合、セッションのロール更新
├ maintenance/  日次バッチ（保持期間の削除・滞留した Run の後始末）
├ domain/       エンティティ・リポジトリ・正規化モデル・列挙値
└ platform/     監査ログ、ArtifactStore、共通例外、設定、可観測性
```

| 規則 | 理由 |
| --- | --- |
| `adapter` と `evaluate` は互いを知らない | パーサは読むだけ、判定は正規化モデルだけを入力にする。ツールを差し替えても判定は変わらない |
| `adapter` と `evaluate` は `config` を知らない | 合格ラインの解決は `pipeline` が行い、解決済みの値だけを渡す |
| `query` は書き込み系（`ingest` / `normalize` / `evaluate`）を呼ばない | 参照系は保存済みの判定結果を読むだけ |
| すべてのモジュールが `platform` に依存してよい。逆は不可 | 共通基盤が業務ロジックを知らない状態を保つ |

依存規則は ArchUnit のテスト（`ModuleDependencyTest`）で検証する。指標やツール形式の追加は、アダプタと判定器の追加で完結する。

### 4.2 Run のライフサイクル

Run は「1 つのコミットに対する 1 回の計測・判定」。**確定後は不変**で、再評価は判定結果だけを差し替える。

```
CREATED ─(成果物)→ UPLOADING ─(finalize)→ FINALIZED → PROCESSING ─┬→ EVALUATED
   │                   │                                          └→ FAILED（処理の失敗。再評価で直せる）
   └───────────────────┴─(24 時間 finalize されない)→ ABANDONED（終端）
```

- **`FAILED`（処理失敗）と `verdict = FAIL`（不合格）は別物**。前者は quality-gate の処理の失敗（合格ラインの誤りなど）、後者は品質が合格ラインを満たさなかったという判定結果
- 同じコミットへの再送信は `attempt` を増やした新しい Run になる。画面は最新の attempt を使う
- 取り込みから判定までは [取り込み](features/ingest/design.md) と [判定](features/evaluation/design.md) にある

### 4.3 エラー処理と可観測性

- API のエラーは RFC 9457（Problem Details）に `errorCode` を足して返す。`detail` には**何をどう直せばよいか**を書く（取り込みの失敗は収集ランナーのログにしか残らないため）
- ログは既定でテキスト、`QG_LOG_FORMAT`（`ecs` / `logstash` / `gelf`）で 1 行 1 JSON。`requestId`（`X-Request-Id`）と `runId` を MDC に載せ、エラー応答の `traceId` と一致させる。Ingest Token・セッション ID・GitHub のトークンは出さない
- ログのレベル: 判定結果は INFO、成果物の形式不正は WARN（日常的に起こる）、想定外の失敗と日次バッチの失敗は ERROR
- メトリクスは Spring Boot の標準（JVM・HTTP・接続プール）を `/actuator/prometheus` で公開する。独自メトリクスは持たない

---

## 5. データベース

DBMS は PostgreSQL 17。**列の定義の正本は `backend/src/main/resources/db/migration/V001__init.sql`** で、ここでは繰り返さない。

### 5.1 方針

| 項目 | 方針 | 理由 |
| --- | --- | --- |
| 主キー | `uuid`（UUIDv7 をアプリで採番。`@GeneratedValue` は使わない） | API に出る ID を推測しにくくしつつ、時系列性でインデックスの局所性を保つ |
| 日時 | `timestamptz`（UTC） | 表示時にタイムゾーンを適用する |
| 列挙 | `varchar` + `CHECK` 制約、JPA は `EnumType.STRING` | `ENUM` 型は値の追加に DDL が要る。序数は値の追加で意味が変わる |
| 半構造データ | `jsonb`（`@JdbcTypeCode(SqlTypes.JSON)`） | 指標ごとに違う内訳（`detail`）を持つ。検索に使う値は列にする |
| 数値 | 判定に関わる値は `numeric` | 丸めで境界値（75.0% など）の判定が変わらないようにする |
| 削除 | 物理削除（保持期間の日次バッチ） | 論理削除フラグは全クエリに条件が増える |
| 関連 | `OneToMany` はマッピングせず、リポジトリのクエリで明示的に取る | N+1 と意図しない遅延ロードを避ける |

### 5.2 テーブル

```
users
repositories ──▶ runs ──┬──▶ artifacts
                        ├──▶ run_skipped_metrics
                        ├──▶ measurements（repository_id も持つ）
                        └──▶ findings
audit_logs（users を参照）
SPRING_SESSION / SPRING_SESSION_ATTRIBUTES
```

| テーブル | 内容 | 要点 |
| --- | --- | --- |
| `users` | 利用者と許可リスト | 行の無い GitHub ユーザーはログインできない。ロールは `ADMIN` / `VIEWER`、状態は `ACTIVE` / `DISABLED` |
| `repositories` | 計測対象 | 初めて計測が届いたときに作られる。`default_branch` は計測ごとに更新する |
| `runs` | 1 コミットに対する 1 回の計測・判定 | `(repository_id, commit_sha, attempt)` で一意。`config_commit_sha` は合格ラインを送った quality-gate のコミット、`baseline_run_id` は比較対象 Run、`tags` はリリース判定でタグを解決するのに使う |
| `run_skipped_metrics` | スキップの申告 | 受理するか（`accepted`）は判定時に合格ラインで決める |
| `artifacts` | 成果物のメタデータ | 実体はローカルファイル（`ArtifactStore`）。合格ライン（`quality-gate-config`）も成果物として持つ |
| `measurements` | 指標ごとの判定結果 | 下記 |
| `findings` | 違反 | `(run_id, fingerprint)` で一意。解消した違反も `RESOLVED` として今回の Run に保存し、比較対象 Run が消えても表示が壊れないようにする |
| `audit_logs` | 監査ログ | 追記のみ。ロール `quality_gate_app` があればマイグレーションで `UPDATE` / `DELETE` を剥奪する |

**`measurements` の要点**

- `variant` は値どうしを比べられるかを分ける計測条件（性能の計測環境名）。前回値は `variant` の一致する行からだけ引き、トレンドの系列も分ける
- `component_name` / `scenario` / `variant` は NULL を取りうるため、一意性は `COALESCE` を挟んだ式インデックス（`ux_measurements_key`）で守る
- `repository_id` と `measured_at` を `runs` から意図的に複製し、トレンドを結合なしで引く（更新されない値に限る）
- 前回値（`previous_value`）は判定時に焼き付ける。比較対象 Run が消えても前回比の表示が壊れない

### 5.3 インデックス

| クエリ | インデックス |
| --- | --- |
| リポジトリごとの最新の判定済み Run と最後の完全計測 | `ix_runs_latest`（`status = 'EVALUATED'` の部分インデックス） |
| Run 一覧（リポジトリ・ブランチ・新しい順） | `ix_runs_list` |
| トレンド（リポジトリ × 指標 × 期間） | `ix_measurements_trend` |
| Run 詳細の指標・違反 | `ix_measurements_run` / `ix_findings_run` |
| 保持期間の削除 | `ix_runs_retention` |
| タグの解決 | `ix_runs_tags`（GIN） |
| 監査ログの一覧 | `ix_audit_logs_occurred` / `ix_audit_logs_target` |

3 年後の想定（5 リポジトリ・100 Run/日）で `findings` が約 1,100 万行、`measurements` が約 220 万行。上のインデックスで足りる。
集計用のテーブルは持たない（判定・再評価・削除のたびに整合させる処理が要るため）。

### 5.4 マイグレーション

| 項目 | 規約 |
| --- | --- |
| 配置と命名 | `backend/src/main/resources/db/migration/V<連番3桁>__<snake_case の説明>.sql` |
| 適用済みのファイル | コメントも含めて変更しない（Flyway のチェックサムが変わり、適用済みの DB で検証に失敗する）。修正は新しいマイグレーションで行う。そのため `V001__init.sql` の先頭のコメントは旧パス（`docs/spec/06-database-design.md`）を指したままになっている（この章のこと） |
| 検証 | `FlywayMigrationIT` が空の DB に全マイグレーションを適用する |

本番の運用を始める前に、それまでのマイグレーションを `V001__init.sql` 1 本にまとめた。

---

## 6. API の共通規則

**API の正本は実装から生成した `api/openapi.yml`**（エンドポイント・リクエスト・応答の型）。ここでは仕様から読み取れない共通の規則だけを記す。
エンドポイントごとの設計は各機能の design.md にある。

| 項目 | 規則 |
| --- | --- |
| ベースパス | `/api/v1` |
| 形式 | JSON。成果物のアップロードだけ `multipart/form-data` |
| 日時 | ISO 8601、UTC。表示のタイムゾーン変換は画面が行う |
| `null` と `0` | `null` は「計測していない」、`0` は「計測して 0 だった」。常に返す項目は `required`、null を返しうる項目は `nullable` を宣言する |
| 表示用の文字列 | 判定理由（`reason`）や計測条件の表示名（`variantLabel`）はサーバが返し、表現を 1 か所に集める |
| しきい値の形 | `threshold` は指標によらず `{ "operator", "value" }` を必ず含む。内訳は追加のキーで添える |
| ページング | カーソル方式（`limit` 既定 20・上限 100、`nextCursor` / `hasMore`）。カーソルは不透明な文字列 |
| 絞り込みの未知の値 | 400。黙って無視すると「絞り込んだのに全件出た」ように見える |
| エラー | Problem Details に `errorCode`（機械可読）・`traceId`・`violations`（入力検証のみ）を足す。クライアントは `title` / `detail` に依存しない |
| エンティティ | そのまま返さない（DB の変更が API の破壊的変更に直結するため） |

### 6.1 認証の経路

| 経路 | 対象 | 方式 |
| --- | --- | --- |
| Ingest Token | `POST /api/v1/runs`・`.../artifacts`・`.../finalize` | `Authorization: Bearer <token>`。書き込み専用で参照 API は呼べない。Cookie を使わないため CSRF の対象外 |
| セッション | それ以外の `/api/**` | GitHub ログイン後の `SESSION` Cookie（HttpOnly / SameSite=Lax）。状態を変える操作には CSRF トークン（`XSRF-TOKEN` Cookie を `X-XSRF-TOKEN` ヘッダで送り返す）を求める |
| なし | `/actuator/health`・`/actuator/info`、SPA のシェル | 公開。`/actuator/**` のほか（`prometheus` など）は ADMIN |

同一オリジンのため CORS は設定しない。詳細は [認証](features/auth/design.md)。

### 6.2 エンドポイント

凡例: 認可の「—」はログインしていれば可（VIEWER 以上）。

| メソッド | パス | 用途 | 認可 | 機能 |
| --- | --- | --- | --- | --- |
| POST | `/api/v1/runs` | Run の作成（初めてのリポジトリは登録） | Ingest Token | [取り込み](features/ingest/design.md) |
| POST | `/api/v1/runs/{runId}/artifacts` | 成果物のアップロード | Ingest Token | 同上 |
| POST | `/api/v1/runs/{runId}/finalize` | 確定し、その場で判定する | Ingest Token | 同上 |
| GET | `/api/v1/me` | ログイン中の利用者とロール | — | [認証](features/auth/design.md) |
| GET | `/api/v1/dashboard` | 全リポジトリのサマリ | — | [ダッシュボード](features/dashboard/design.md) |
| GET | `/api/v1/repositories/{id}` | リポジトリ詳細 | — | [Run の閲覧](features/run-detail/design.md) |
| GET | `/api/v1/runs` | Run 一覧（`repositoryId` 必須） | — | 同上 |
| GET | `/api/v1/runs/{runId}` | Run 詳細 | — | 同上 |
| GET | `/api/v1/runs/{runId}/findings` | 違反一覧 | — | 同上 |
| GET | `/api/v1/runs/{runId}/artifacts`、`.../{artifactId}/content` | 成果物の一覧とダウンロード | — | 同上 |
| GET | `/api/v1/repositories/{id}/trends` | 指標の時系列 | — | [トレンド](features/trends/design.md) |
| GET | `/api/v1/repositories/{id}/config` | 直近の Run に送られた合格ラインと検証結果 | — | [合格ライン](features/gate-config/design.md) |
| GET | `/api/v1/repositories/{id}/release-report`、`release-report.csv` | リリース判定と CSV | — | [リリース判定](features/release-report/design.md) |
| POST | `/api/v1/runs/{runId}/reevaluate` | 再評価 | ADMIN | [判定](features/evaluation/design.md) |
| GET / POST / PATCH | `/api/v1/users`、`/api/v1/users/{id}` | 許可リスト・ロールの管理 | ADMIN | [管理](features/admin/design.md) |
| GET | `/api/v1/audit-logs` | 監査ログ | ADMIN | 同上 |
| GET | `/actuator/health`、`/actuator/prometheus` | ヘルスチェック / メトリクス | 公開 / ADMIN | — |

API の仕様は起動中のアプリの `/swagger-ui.html` でも見られる。

### 6.3 エラーコード

| `errorCode` | HTTP | 意味 |
| --- | --- | --- |
| `VALIDATION_FAILED` | 400 | 入力の検証エラー（`violations` に詳細） |
| `UNAUTHENTICATED` / `TOKEN_INVALID` | 401 | 未認証 / Ingest Token が不正 |
| `USER_NOT_ALLOWLISTED` / `USER_DISABLED` / `FORBIDDEN` | 403 | 許可リストに無い / アカウントが無効 / 権限不足 |
| `RESOURCE_NOT_FOUND` | 404 | 対象が無い（存在しない URL も含む） |
| `METHOD_NOT_ALLOWED` / `NOT_ACCEPTABLE` | 405 / 406 | 使えないメソッド / 応答できない形式 |
| `RUN_ALREADY_FINALIZED` | 409 | 確定済みの Run への操作 |
| `RUN_NOT_EVALUABLE` | 409 | 確定していない Run の再評価 |
| `ARTIFACTS_DELETED` | 409 | 成果物が保持期間で削除済み |
| `USER_ALREADY_EXISTS` / `ADMIN_REQUIRED` | 409 | 同じ利用者がいる / 管理者がいなくなる変更 |
| `ARTIFACT_TOO_LARGE` | 413 | 1 ファイル 50MB、または Run 合計 200MB の超過 |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | 本文の形式に対応していない |
| `ARTIFACT_TYPE_UNKNOWN` / `ARTIFACT_FORMAT_INVALID` | 422 | 未知の成果物種別 / 形式が不正 |
| `PERFORMANCE_METADATA_MISSING` | 422 | 性能の `environment` が無い |
| `CONFIG_VALIDATION_FAILED` | 422 | 合格ラインの検証エラー（Run の処理失敗の理由にも使う） |
| `INTERNAL_ERROR` | 500 | 想定外の例外 |

### 6.4 OpenAPI 仕様の生成

springdoc がコントローラと DTO から仕様を作り、結合テスト（`OpenApiExportIT`）が `api/openapi.yml` に書き出してコミットする。
フロントエンドはそこから型（`frontend/src/api/schema.d.ts`）を生成する。PR の CI は再生成して差分が無いことを確かめる（[開発環境](development.md#5-api-の型と応答例の生成)）。

- 入れ子の型には応答をまたいで一意な名前を付ける（springdoc はスキーマ名に Java の単純名を使い、同名の型を静かに上書きする）
- 必ず返す項目に `@NotNull`、null を返しうる項目に `@Schema(nullable = true)` を付ける（付けないと生成型が全項目省略可能になる）
- 列挙値は文字列で返す

---

## 7. 画面の共通規則

画面ごとの設計は各機能の design.md にある。

### 7.1 画面一覧

| # | 画面 | パス | 閲覧 | 機能 |
| --- | --- | --- | --- | --- |
| S-01 | ダッシュボード | `/` | 全員 | [ダッシュボード](features/dashboard/design.md) |
| S-02 | リポジトリ詳細 | `/repositories/:repositoryId` | 全員 | [Run の閲覧](features/run-detail/design.md) |
| S-03 | Run 詳細 | `/runs/:runId` | 全員（再評価は ADMIN） | 同上 |
| S-04 | 違反一覧 | `/runs/:runId/findings` | 全員 | 同上 |
| S-05 | トレンド | `/repositories/:repositoryId/trends` | 全員 | [トレンド](features/trends/design.md) |
| S-06 | 設定 | `/repositories/:repositoryId/config` | 全員（表示のみ） | [合格ライン](features/gate-config/design.md) |
| S-07 | 管理 | `/admin/users`・`/admin/audit-logs` | ADMIN | [管理](features/admin/design.md) |
| S-08 | リリース判定 | `/repositories/:repositoryId/release?ref=` | 全員 | [リリース判定](features/release-report/design.md) |
| — | ログイン / アクセス拒否 / 見つからない | `/login` / `/forbidden` / その他 | 認証不要 | [認証](features/auth/design.md) |

```
/login ─(GitHub ログイン + 許可リスト)─┬→ S-01 ダッシュボード ─→ S-02 リポジトリ詳細 ─┬→ S-03 Run 詳細 ─→ S-04 違反一覧 ─→ GitHub の該当行
                                        │         └───────────→ S-03（最新の Run）    ├→ S-05 トレンド
                                        └→ /forbidden                                  ├→ S-06 設定
                                                                                       └→ S-08 リリース判定
```

### 7.2 レイアウト

| 要素 | 仕様 |
| --- | --- |
| ヘッダ | 固定。ナビは現在地を `aria-current="page"` で示す。管理は ADMIN にだけ出す |
| テーマ切替 | ライト / ダーク / OS 追従。選択を `localStorage` に保持し、`<html data-theme>` に反映する |
| 利用者メニュー | 表示名、ロール、ログアウト |
| 最大幅 | 1440px。中央寄せ、左右 24px（モバイルは 16px）の余白 |

### 7.3 ステータスの表現

**色だけで合否を伝えない**。ラベル・記号・アイコン・色の 4 つを常に同時に使い、色覚特性・モノクロ印刷・強制カラーモードでも意味が失われないようにする。

| ステータス | ラベル | 記号 | アイコン | 色 |
| --- | --- | :---: | --- | --- |
| `PASS` | 合格 | ● | `pi-check-circle` | `#0ca30c` |
| `WARN` | 注意 | ▲ | `pi-exclamation-triangle` | `#fab219` |
| `FAIL` | 不合格 | ■ | `pi-times-circle` | `#d03b3b` |
| `ERROR` | 計測エラー | ◆ | `pi-question-circle` | `#ec835a` |
| `SKIP` | 未計測 | ○ | `pi-minus-circle` | `#898781` |
| `NOT_APPLICABLE` | 対象外 | — | `pi-ban` | `#898781` |

- `SKIP` と `NOT_APPLICABLE` は「良い / 悪い」を表さないため中立色にし、記号とラベルで区別する。`NOT_APPLICABLE` の値の欄は「—」にする
- **色は点・記号・アイコンにだけ使い、文字色には使わない**。`WARN` と `ERROR` はライト面でのコントラストが 3:1 を下回るため、ラベルは常に本文の色で描く
- **処理失敗（`status = FAILED`）は不合格（`verdict = FAIL`）と同じ赤で出さない**。◆「処理失敗」+「判定できませんでした」と明記する。前者は基盤の管理者が、後者は開発者が直すもので、動くべき人が違う
- 色の正本は `frontend/src/styles/tokens.css`。ステータス色はライト / ダークで同じ値。`--text-muted` と `--link` はモードごとに違う値を持つため、アクセシビリティの検査は両モードで行う
- グラフの系列色は 3 色（色覚特性の下でも見分けられる）。系列の色はサーバが `colorIndex` で固定し、絞り込みで系列が減っても塗り替わらない

### 7.4 画面の状態

| 状態 | 表示 |
| --- | --- |
| 読み込み中 | 「読み込み中…」の文言 |
| 空 | 何が無いかと、次に取るべき操作 |
| エラー | 何が起きたかと再試行ボタン（`role="alert"`） |
| 権限なし | 操作ボタンを隠さず無効化し、理由を示す（機能の存在を知らせ、管理者に依頼する発想を残すため）。ADMIN 専用の画面は `/forbidden` へ移る |

### 7.5 レスポンシブ

| 幅 | レイアウト |
| --- | --- |
| 768px 以上 | 指標の表を全列表示 |
| 768px 未満 | 指標の表を**カード形式に変形**する（横スクロールさせると判定列が見えなくなる） |

375px 幅で確認する。

### 7.6 状態管理と API 呼び出し

| ストア | 保持する状態 |
| --- | --- |
| `useAuthStore` | ログイン中の利用者とロール（`isAdmin`） |
| `useDashboardStore` | ダッシュボードのサマリ、ポーリング |
| `useRunStore` | 表示中の Run 詳細 |
| `useFindingsStore` | 表示中の Run の違反一覧と絞り込み条件 |
| `useTrendStore` | 選択中の指標・期間、取得した系列 |
| `useUiStore` | テーマ、トースト |

- API はすべて `src/api/client.ts` の `openapi-fetch` クライアント経由で呼ぶ。型は生成された `schema.d.ts` をそのまま使い、再定義しない（手書きの型が混ざると、API の変更で黙って型が合わなくなる箇所が生まれる）
- キャッシュは持たず、画面遷移のたびに取得する（古い判定結果を表示する事故のほうが重い）
- 読み込みの失敗はストアの状態として持ち画面内に出す。登録・更新などの操作の失敗はトーストで知らせる
- 画面側のロール判定は表示の都合だけで、権限の境界はバックエンドの認可が担う

### 7.7 アクセシビリティ

quality-gate 自身も WCAG 2.2 AA を満たす。PR の CI で axe-core の critical / serious 違反 0 件を確かめる（[開発環境](development.md#6-アクセシビリティ検査)）。

| # | 要件 |
| --- | --- |
| A-1 | すべての機能をキーボードだけで操作できる |
| A-2 | フォーカスを常に見えるようにする。PrimeVue の既定のリングを消さない |
| A-3 | ステータスを色だけで伝えない（7.3） |
| A-4 | グラフはインライン SVG で描き、`role="img"` と要約の `aria-label` を付け、表形式の代替を DOM に置く |
| A-5 | 見出しレベルを飛ばさない。各画面に `<h1>` が 1 つ |
| A-6 | 入力欄に `<label>` を関連付ける。エラーは `aria-describedby` で結び、`aria-invalid` を付ける |
| A-7 | 非同期の結果（保存完了、再評価の結果）を `aria-live="polite"` で知らせる |
| A-8 | ダイアログはフォーカスを閉じ込め、閉じたら開いた要素へ戻す |
| A-9 | スキップリンクでヘッダを飛ばして本文へ移れる |
| A-10 | 本文のコントラスト比 4.5:1 以上、UI 部品と図形は 3:1 以上 |
| A-11 | `prefers-reduced-motion` を尊重する |
| A-12 | 画面ごとに `<title>` を設定し、遷移時に更新する |

自動検査で見つかるのは WCAG 違反の一部だけで、キーボード操作とフォーカス順序の手動確認をリリース前のチェックリストに含める。

---

## 8. 非機能要件と制約

| 項目 | 要件 |
| --- | --- |
| 規模 | 対象リポジトリ 1〜5、Run は 1 日 10〜100、利用者は数名〜20。**PostgreSQL 1 台 + アプリ 1 プロセス**（Docker Compose）で足り、キュー・キャッシュ・水平スケールは持たない |
| 性能の目安 | ダッシュボード p95 1.0 秒、Run 詳細 p95 1.5 秒、確定から判定結果の応答まで p95 60 秒（自動では検証しない） |
| 可用性 | quality-gate の障害が対象リポジトリの開発を止めない。DB は日次バックアップ |
| セキュリティ | 通信は TLS。Ingest Token と GitHub のクライアントシークレットは環境変数で渡し、ログに出さない。成果物の閲覧は許可リストの利用者に限る。アップロードはサイズ上限と形式検証を行い、XML の外部実体参照を無効にし、JSON は深さとサイズに上限を設ける。計測中の対象のコードはコンテナの中で動かし、認証情報を渡さない |
| 運用 | `/actuator/health`、Prometheus のメトリクス、相関 ID 付きのログ |
| 保守性 | 指標やツール形式の追加がアダプタと判定器の追加で完結する。quality-gate 自身の品質は PR の CI で確かめる（DD-5） |

| # | 制約 |
| --- | --- |
| C-1 | 計測は収集ランナー（専有のセルフホストランナー、1 台で直列）が行う。バックエンドはコードを取得しない |
| C-2 | 計測できる対象は Maven + npm / Vitest のモノレポに限る |
| C-3 | ミューテーションテストは backend のみ |
| C-4 | 自動のアクセシビリティ検査は WCAG 違反の一部しか検出できない |
| C-5 | 社内専用・単一テナント。GitHub は個人アカウント（Free）のため、ログインの可否は許可リストで制御する |

---

## 9. リスクと未決事項

| # | リスク | 対策 |
| --- | --- | --- |
| R-1 | 性能の値が実行環境のノイズで揺れる | 専有のランナー、ウォームアップの除外、3 回実行の中央値 |
| R-2 | PIT と負荷試験で計測が長くなる | PR の計測では実行しない（DD-8） |
| R-3 | 新しい CVE の公開で、コードを変えずに不合格になる | 計測し直すと判定が変わる。修正版が無い間は合格ラインの変更として扱い、理由をコミットに残す |
| R-4 | 合格ラインの緩和が乱発されゲートが形骸化する | 合格ラインは Git で管理し、変更はプルリクエストを通す（DD-13） |
| R-5 | スキップが常態化し、M-02 と M-03 / M-04 が測られなくなる | 申告制と許容リスト、部分計測の明示、最後の完全計測の表示、リリース判定は完全計測を求める |
| R-6 | 許可リストの設定漏れで、意図しないユーザーがログインする | 既定を拒否とし、変更は監査ログに残す |
| R-7 | 既存コードに複雑度の高い関数が多く、M-06 が不合格のままになる | 許容する関数は `exclusions` で外し、理由をコミットに残す |
| R-8 | 対象のビルド構成が変わり、収集ランナーが追従できない | 未提出は ERROR として表に出る。計測プロファイルを更新する |

| # | 未決事項 | 現状 |
| --- | --- | --- |
| Q-1 | ミューテーションテストの実行時間をどこまで許容するか | PR 以外の計測で全量を実行している（like-chatgpt で約 1 分）。ランナーの占有が問題になったら見直す |
| Q-2 | 不合格時の是正プロセス | マージを止めないため、不合格を放置しない運用ルール（是正までの目標期間、レビューの場、マージブロックへ移る判断基準）が別に要る |
| Q-3 | 保持期間とストレージ | 既定は Run 2 年・成果物 90 日・監査ログ 2 年。より長い保持が要るか、成果物の置き場（初期 10GB 程度）を確保できるか |
| Q-4 | デプロイ先と運用体制 | オンプレミスかクラウドか、監視・バックアップの担当。規模としては Docker Compose の単一ホストで足りる |
| Q-5 | マージブロックへ進む場合の GitHub プラン | GitHub Free ではプライベートリポジトリの保護ブランチを使えない（Pro 以上が必要） |
