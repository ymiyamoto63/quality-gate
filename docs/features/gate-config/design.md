# 合格ライン: 設計

要件は [requirements.md](requirements.md)。各指標のしきい値の意味は [指標](../../metrics.md)。

## 1. 置き場所と流れ

合格ラインは quality-gate リポジトリの `collector/targets/<owner>__<name>.gate.yml` **だけ**に置く（DD-13）。

```
*.gate.yml を PR で変更 → main にマージ → 次の計測で収集ランナーが quality-gate-config として送る → その内容で判定
```

- 収集ランナーは合格ラインが無ければ Run を作らずに止まる（既定値で黙って判定すると、意図しない基準で合否が出る）
- Run には合格ラインを送った quality-gate のコミット（`config_commit_sha`）を記録し、Run 詳細に表示する
- 送られた合格ラインは Run の成果物として残り、保持期間の削除の対象外（Run と同じ期間残る）。**再評価は Run とともに送られた合格ラインで判定し直す**
- 合格ラインの無い Run（手動で送ったものだけ）はシステムの既定値で判定する
- バックエンドが GitHub から合格ラインを取りに行く方式は採らない（GitHub の障害で判定が止まり、認証情報も要る）。DB でも版を管理しない

## 2. 書式

```yaml
version: 1                        # 必須。対応している版は 1

execution:
  skippable_metrics: [mutation_score, performance]   # PR の計測で申告してよいスキップ（既定も同じ）

exclusions:                       # 計測から外すファイル（glob。指標の「計測の対象範囲」を参照）
  - "**/generated/**"

metrics:
  branch_coverage:        { enabled: true, threshold: 75, warn_below: 80 }
  mutation_score:         { enabled: true, threshold: 60, components: [backend] }
  performance:            { enabled: true, p95_ms: 500, arrival_rate_rps: 50, error_rate_pct: 0.1, scenarios: [login, search] }
  vulnerabilities:        { enabled: true, max_critical: 0, max_high: 0 }
  cyclomatic_complexity:  { enabled: true, max_complexity: 15, warn_from: 11 }
  api_contract:           { enabled: true, breaking_changes: 0 }
  accessibility:          { enabled: true, standard: wcag22aa, max_critical: 0, pages: ["/", "/login"] }
  test_results:           { enabled: true, min_success_rate: 100, min_test_count: 1, max_skipped_increase: 0 }   # max_skipped も書ける
  secrets:                { enabled: false, max_secrets: 0 }
  licenses:               { enabled: false, max_forbidden: 0 }   # max_restricted / max_unknown も書ける
```

| 指標名（キー） | 指標 | 書ける項目 |
| --- | --- | --- |
| `branch_coverage` | M-01 | `threshold`・`warn_below` |
| `mutation_score` | M-02 | `threshold`・`components` |
| `performance` | M-03 / M-04 | `p95_ms`・`arrival_rate_rps`・`error_rate_pct`・`scenarios` |
| `vulnerabilities` | M-05 | `max_critical`・`max_high` |
| `cyclomatic_complexity` | M-06 | `max_complexity`・`warn_from` |
| `api_contract` | M-07 | `breaking_changes` |
| `accessibility` | M-08 | `standard`・`max_critical`・`pages` |
| `test_results` | M-09 / M-10 | `min_success_rate`・`min_test_count`・`max_skipped`・`max_skipped_increase` |
| `secrets` | M-11 | `max_secrets` |
| `licenses` | M-12 | `max_forbidden`・`max_restricted`・`max_unknown` |

どの指標にも `enabled` を書ける。書かなかった指標・項目は既定値（上の例の値）になり、**`secrets` と `licenses` だけは既定で無効**。

`enabled: false` の指標は判定せず、Run にも現れない。収集ランナーもその指標を計測しない（[運用](../../operations.md#43-計測する指標の切り替え)）。
YAML 1.1 として読むため、引用符の無い `no` / `off` も false になる。`"false"` のような文字列は有効のまま。

## 3. 検証

検証エラーは**行番号とキーのパス付き**で記録し、Run 詳細と設定の画面で原因が分かるようにする。検証エラーのある Run は処理失敗（`CONFIG_VALIDATION_FAILED`）になる。
不正な設定で判定を続けると、意図しないしきい値で合格が出てしまうためである。

| 規則 | 理由 |
| --- | --- |
| 未知のキーはエラー。綴りの近い候補を添える | typo を黙って無視すると、設定したつもりの値が効かないまま合格が出続ける |
| 重複したキーはエラー | 後勝ちにすると、消したはずの設定が効き続ける |
| 割合は 0〜100、件数は 0 以上。文字列で書いた数値（`"75%"`）はエラー | 暗黙の変換を通すと、書いた値と効く値がずれる |
| `skippable_metrics` は既知の指標名だけ | 綴りを間違えた指標のスキップが受理されず、原因の分かりにくい ERROR になる |
| `components` はコンポーネント名の配列、`pages` は `/` で始まるパスの配列、`standard` は既知の基準だけ | |
| `min_test_count` は 1 以上 | 実行 0 件を合格にしない |

## 4. 設定の画面（S-06）

`GET /api/v1/repositories/{id}/config` は、そのリポジトリで直近に計測された Run に送られた合格ラインを**検証し直して**、行番号つきのエラーとともに返す
（画面が該当行の直下にエラーを出すため、構造で持つ必要がある）。

```
┌───────────────────────────────────────────────────────────────────┐
│ 設定 — example/sample                                             │
│ 直近の Run（2026-09-20）に送られた合格ライン · quality-gate 4c5d6e7 │
├───────────────────────────────────────────────────────────────────┤
│  1 │ version: 1                                                   │
│ ...                                                               │
│ 15 │     threshold: "75%"                                         │
│    │ ⚠ 0〜100 の数値を指定してください（受信値: "75%"）            │
└───────────────────────────────────────────────────────────────────┘
```

- 表示だけで編集はできない。合格ラインは `*.gate.yml` で管理し、変更はプルリクエストで行って main にマージした後の計測から使われることを画面に示す
- 検証エラーは一覧にまとめず、**該当行の直下**に出す（どの行の話か照合する手間をなくす）
