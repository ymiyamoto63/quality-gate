# CI 用のセルフホストランナー

`.github/workflows/quality-gate.yml` は、計測ジョブ（`base` / `mutation` / `performance`）を
既定で **セルフホストランナー**（`runs-on: self-hosted`）で実行します。理由は 2 つです。

- 実行時間の長い PIT と k6 を GitHub ホストランナーで回すと、Actions の無料枠（月 2,000 分）を使い切ってしまう。
  セルフホストランナーの実行時間は無料枠を消費しない
- 性能指標（M-03〜05）は専有環境で計測したときだけ判定に使う（D-7）。GitHub ホストランナーでの計測値は
  `REFERENCE`（参考値）になる（[指標・判定仕様](../initial/02-metrics-spec.md)「ランナー種別の影響」）

なお `setup` と `submit` の 2 ジョブは短時間で終わるため、常に `ubuntu-latest` で動きます。

## 1. マシンの準備

ランナーを置くマシン（Linux x64 を想定。WSL2 でも可）に次を用意します。
JDK と Node.js は `actions/setup-java` / `actions/setup-node` がジョブごとに取得するため、事前の導入は不要です。

| 必要なもの | 使う箇所 |
| --- | --- |
| Docker（ランナーを動かすユーザーを `docker` グループに入れる） | 結合テストの Testcontainers、oasdiff（`docker run tufin/oasdiff`） |
| git | チェックアウトと merge-base の解決 |
| パスワードなしの `sudo`、または Playwright の依存パッケージの事前導入 | `npx playwright install --with-deps chromium` が apt で OS パッケージを入れる |
| github.com / Maven Central / npm レジストリへの外向き通信 | ランナーの接続、JDK・Node.js・依存関係の取得 |
| 十分なディスク（目安 20GB 以上） | Maven / npm のキャッシュ、Docker イメージ、Playwright のブラウザ |

性能計測の条件として **他のジョブと同居させない**ことが決まっているため
（[指標・判定仕様](../initial/02-metrics-spec.md)「計測条件」）、
専有マシンに 1 台だけ登録し、他のリポジトリのランナーや常駐サービスは同じマシンに置かないでください。

## 2. ランナーの登録

1. GitHub のリポジトリで **Settings → Actions → Runners → New self-hosted runner** を開き、
   OS に Linux、アーキテクチャに x64 を選ぶ
2. 画面に表示されるコマンドをマシン上で順に実行し、ランナーをダウンロード・展開する
3. 同じく画面に表示される登録コマンドを実行する（トークンは画面に出るものを使う。有効期限は 1 時間）

   ```bash
   ./config.sh --url https://github.com/ymiyamoto63/quality-gate --token <登録トークン>
   ```

   ラベルの入力は既定のまま（`self-hosted`, `Linux`, `X64`）で構いません。
   ワークフローは `self-hosted` ラベルだけで割り当てます
4. サービスとして常駐させる（マシンの再起動後も自動で起動する）

   ```bash
   sudo ./svc.sh install
   sudo ./svc.sh start
   sudo ./svc.sh status   # active (running) になっていれば OK
   ```

   WSL2 で `svc.sh` を使うには systemd の有効化（`/etc/wsl.conf` の `[boot]` に `systemd=true`）が必要です。
   有効化しない場合は、ターミナルで `./run.sh` を起動したままにします

登録後、**Settings → Actions → Runners** でランナーが `Idle` と表示されれば準備完了です。

## 3. リポジトリ変数とシークレットの設定

**Settings → Secrets and variables → Actions** で次を設定します。

| 種別 | 名前 | 値 | 説明 |
| --- | --- | --- | --- |
| Variables | `QG_RUNNER` | `self-hosted`（既定）/ `ubuntu-latest` | 計測ジョブを実行するランナー。未設定なら `self-hosted` |
| Variables | `QG_RUN_HEAVY_ON_GITHUB` | `false`（既定）/ `true` | GitHub ホストランナーでも PIT / k6 を実行するか。セルフホストでは常に実行する |
| Variables | `QG_BASE_URL` | 例: `https://quality-gate.example.com` | 取り込み先の quality-gate の URL |
| Secrets | `QG_INGEST_TOKEN` | `qg_<prefix>_<secret>` | リポジトリ単位の Ingest Token（[取り込み](ingest.md)を参照） |

## 4. 動作確認

**Actions → quality-gate → Run workflow** で `runner` に `self-hosted` を選んで実行し、
`base` / `mutation` / `performance` がセルフホストランナーで動くことを確かめます。

## ランナーを止めるとき

セルフホストランナーが停止していると、計測ジョブはランナーの空きを待ったまま進みません。
保守などで止める期間は、リポジトリ変数 `QG_RUNNER` を `ubuntu-latest` に変えてください。
このとき PIT / k6 はスキップとして申告され（`QG_RUN_HEAVY_ON_GITHUB` が `false` の場合）、
Run は部分計測として記録されます。完全計測が `execution.full_measurement_interval_days`（既定 7 日）を
超えて途絶えると警告が出るため、復旧したら `self-hosted` に戻します。

> **注意**: セルフホストランナーは、ワークフローを動かせる人なら誰でもそのマシン上で任意のコードを実行できます。
> リポジトリを公開する場合は、フォークからの Pull Request がセルフホストランナーで動かないよう
> **Settings → Actions → General** で外部コントリビューターのワークフロー実行に承認を必須にするか、
> `QG_RUNNER` を `ubuntu-latest` にしてください。
