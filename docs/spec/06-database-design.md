# quality-gate データベース設計

本書は quality-gate のテーブル構成と、その設計の理由を定める（DBMS は PostgreSQL 17）。
**列の定義そのものは `backend/src/main/resources/db/migration/V001__init.sql` が正本**であり、本書では繰り返さない。

---

## 1. 設計方針

| 項目 | 方針 | 理由 |
| --- | --- | --- |
| 主キー | `uuid`（**UUIDv7** をアプリ側で採番） | API に出る ID を推測しにくくしつつ、v7 の時系列性でインデックスの局所性を保つ |
| 日時 | `timestamptz`（UTC で保存） | 表示時にタイムゾーンを適用する |
| 列挙 | `varchar` + `CHECK` 制約 | PostgreSQL の `ENUM` 型は値の追加に DDL が要る |
| 半構造データ | `jsonb` | 指標ごとに異なる内訳（`detail`）を持つ。検索に使う値は列にする |
| 削除 | 物理削除（保持期間の日次バッチ） | 論理削除フラグは全クエリに条件が増える |
| 数値 | 判定に関わる値は `numeric` | 浮動小数の丸めで境界値（75.0% など）の判定が変わらないようにする |

---

## 2. テーブル

```
users
repositories ──▶ runs ──┬──▶ artifacts
                        ├──▶ run_skipped_metrics
                        ├──▶ measurements（repository_id も持つ）
                        └──▶ findings
audit_logs（users を参照）
SPRING_SESSION / SPRING_SESSION_ATTRIBUTES
```

| テーブル | 内容 | 設計上の要点 |
| --- | --- | --- |
| `users` | 利用者と許可リスト | 行の無い GitHub ユーザーはログインできない。ロールは `ADMIN` / `VIEWER` |
| `repositories` | 計測対象リポジトリ | 収集ランナーが初めて計測を送ったときに作られる。`default_branch` は計測ごとに送られる値で更新する |
| `runs` | 1 コミットに対する 1 回の計測・判定 | `(repository_id, commit_sha, attempt)` で一意。`config_commit_sha` は合格ラインを送った quality-gate のコミット。`baseline_run_id` は比較対象 Run。`tags` はリリース判定でタグをコミットに解決するのに使う |
| `run_skipped_metrics` | スキップの申告 | 受理するか（`accepted`）は判定時に合格ラインで決める |
| `artifacts` | 取り込んだ成果物のメタデータ | 実体はローカルファイル（`ArtifactStore`）。合格ライン（`quality-gate-config`）も成果物として持つ |
| `measurements` | 指標ごとの判定結果 | 下記 |
| `findings` | 判定の根拠となる個別違反 | `(run_id, fingerprint)` で一意。解消した違反も `RESOLVED` として今回の Run に保存し、比較対象 Run が消えても表示が壊れないようにする |
| `audit_logs` | 監査ログ | 追記のみ。本番のアプリ用ロールからは `UPDATE` / `DELETE` を剥奪する |

**`measurements` の要点**

- `variant` は**値どうしを比べられるかを分ける計測条件**（M-02 の実行範囲、性能の計測環境名）。前回値（`previous_value`）は `variant` が一致する行からだけ引き、トレンドの系列も分ける
- `component_name` / `scenario` / `variant` は NULL を取りうるため、一意性は `COALESCE` を挟んだ式インデックス（`ux_measurements_key`）で守る。UNIQUE 制約は NULL 同士を重複と見なさない
- `repository_id` と `measured_at` を `runs` から意図的に複製する。トレンド検索を結合なしで引くため（更新されない値に限る）
- 前回値は判定時に焼き付ける。比較対象 Run が保持期間で消えても前回比の表示が壊れない

---

## 3. インデックス

| クエリ | インデックス |
| --- | --- |
| リポジトリごとの最新の判定済み Run と最後の完全計測 | `ix_runs_latest`（`status = 'EVALUATED'` の部分インデックス） |
| Run 一覧（リポジトリ・ブランチ・新しい順） | `ix_runs_list` |
| **トレンド**（リポジトリ × 指標 × 期間） | `ix_measurements_trend` |
| Run 詳細の指標・違反 | `ix_measurements_run` / `ix_findings_run` |
| 保持期間の削除 | `ix_runs_retention` |
| タグの解決 | `ix_runs_tags`（GIN） |

3 年後の想定（5 リポジトリ・100 Run/日）で `findings` が約 1,100 万行、`measurements` が約 220 万行になる。
PostgreSQL にとって大きな負荷ではなく、上のインデックスで足りる。

---

## 4. 保持期間と削除

既定値。日数は環境変数（`QG_RETENTION_RUN_DAYS` / `QG_RETENTION_ARTIFACT_DAYS` / `QG_RETENTION_AUDIT_LOG_DAYS`）で変えられる。
誤って短い日数を設定すると大半のデータが消えるため、下限（Run 30 日・成果物 1 日・監査ログ 365 日）を下回る値ではアプリが起動しない。

| 対象 | 保持期間 | 削除方法 |
| --- | --- | --- |
| 成果物のファイル実体 | 90 日（合格ラインは Run と同じ期間） | ファイルを消し、`artifacts.deleted_at` を設定する |
| `runs` とその子（`artifacts` / `measurements` / `findings` など） | 2 年 | `runs` を消し、`ON DELETE CASCADE` で連鎖する |
| `audit_logs` | 2 年 | 管理ロールのバッチで消す |

削除は日次バッチで少量ずつ（1 回あたり最大 10,000 行）行う。一括削除は長いロックと WAL の急増を招く。

---

## 5. マイグレーション

| 項目 | 規約 |
| --- | --- |
| 配置 | `backend/src/main/resources/db/migration/` |
| 命名 | `V<連番3桁>__<snake_case の説明>.sql` |
| 適用済みファイル | 変更しない。修正は新しいマイグレーションで行う |
| 検証 | `FlywayMigrationIT` が空の DB に全マイグレーションを適用する |

本番の運用を始める前に、それまでのマイグレーションを `V001__init.sql` 1 本にまとめた。

---

## 6. JPA マッピング

| 項目 | 方針 |
| --- | --- |
| ID | アプリで生成した UUIDv7 を設定する（`@GeneratedValue` は使わない） |
| 列挙 | `@Enumerated(EnumType.STRING)`（序数は値の追加で意味が変わる） |
| `jsonb` | `@JdbcTypeCode(SqlTypes.JSON)` |
| 関連 | `OneToMany` はマッピングせず、必要なデータはリポジトリのクエリで明示的に取る（N+1 と意図しない遅延ロードを避ける） |
