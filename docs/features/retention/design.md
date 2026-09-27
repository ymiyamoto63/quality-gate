# 保持期間と日次バッチ: 設計

要件は [requirements.md](requirements.md)。設定値は [運用](../../operations.md#22-設定値)。

## 1. 日次バッチ（`ScheduledMaintenance`）

| 処理 | 時刻（既定） | 内容 |
| --- | --- | --- |
| 保持期間の削除（`RetentionCleanup`） | 毎日 03:00 | 成果物のファイル・Run・孤児ファイル・監査ログを消す |
| 滞留した Run の後始末（`StaleRunCleanup`） | 毎日 03:10 | 24 時間 `finalize` されない Run（`CREATED` / `UPLOADING`）を `ABANDONED` にする |

- `@Scheduled` で動かす。時刻は `application.yml` の `quality-gate.schedule.*`（cron。`-` で無効）、タイムゾーンは `QG_SCHEDULE_ZONE`（既定 `Asia/Tokyo`）
- 失敗しても翌日にもう一度動く。単一プロセスのため多重実行の排他（ShedLock など）は要らない
- 滞留した Run を片付けるのは、放置するとダッシュボードの最新の Run が処理中のまま見えるため

## 2. 保持期間

| 対象 | 既定 | 下限 | 環境変数 | 削除の方法 |
| --- | --- | --- | --- | --- |
| 成果物のファイル | 90 日 | 1 日 | `QG_RETENTION_ARTIFACT_DAYS` | ファイルを消し、`artifacts.deleted_at` を設定する（メタデータは残す）。**合格ライン（`quality-gate-config`）は対象外**で、Run と同じ期間残る |
| `runs` とその子（`artifacts` / `measurements` / `findings` など） | 730 日 | 30 日 | `QG_RETENTION_RUN_DAYS` | `runs` を消し、`ON DELETE CASCADE` で連鎖させる |
| `audit_logs` | 730 日 | 365 日 | `QG_RETENTION_AUDIT_LOG_DAYS` | 消す。アプリのロールに `DELETE` 権限が無ければ（本番の想定）警告を出して何もしないため、管理用のロールのバッチで消す |
| 孤児ファイル | — | — | — | DB に記録の無い成果物のファイルを消す。書き込み中のファイルを取り違えないよう、1 日たったものだけ |

- 環境変数を設定しない（または 0）と既定値になる。下限を下回る値ではアプリが起動しない
- 削除は少しずつ行う（Run・監査ログは 1 回 10,000 行、成果物のファイルは 500 件ずつ）。一括の削除は長いロックと WAL の急増を招く
- 期間の経過は、Run は計測日時（`measured_at`）、成果物はアップロード日時、監査ログは記録日時で数える

**成果物が消えた後の影響**: 成果物のファイルが 1 つでも消えた Run は再評価できない（`409 ARTIFACTS_DELETED`）。判定結果・違反・前回値は DB にあるため、画面の表示とリリース判定は Run の保持期間の間は変わらない。
