# セルフホストランナー

quality-gate リポジトリの**収集ランナー**（`collect.yml` / `collect-target.yml` の `plan` / `fetch` / `measure` / `submit` のすべて）が、
**セルフホストランナー**（`runs-on: self-hosted`）で動きます。15 分ごとの定期実行と手動実行で対象リポジトリを計測します
（[収集ランナーで計測する](collector.md)）。常にセルフホストランナーで動きます。

Pull Request の CI（`ci.yml`）は GitHub ホストランナー（`ubuntu-latest`）で動き、セルフホストランナーを使いません。
ランナーが止まっていても PR の CI は止まりません。quality-gate 自身を計測するワークフローはありません（D-18）。

## 1. マシンの準備

ランナーを置くマシン（Linux x64 を想定。WSL2 でも可）に次を用意します。
JDK と Node.js は `actions/setup-java` / `actions/setup-node` がジョブごとに取得するため、事前の導入は不要です。

| 必要なもの | 使う箇所 |
| --- | --- |
| Docker（ランナーを動かすユーザーを `docker` グループに入れる） | 計測用のコンテナ、oasdiff（`docker run tufin/oasdiff`）、Trivy |
| git | チェックアウトと merge-base の解決 |
| curl / unzip / jq | 収集ランナー（`collect.yml`）の PMD の取得と送信（[収集ランナーで計測する](collector.md)） |
| Chromium の依存パッケージ（計測用のコンテナを使わない `ISOLATION=none` の場合のみ） | M-10 の検査。コンテナで計測する場合はイメージに入っている |
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

収集ランナーの変数とシークレット（`QG_BASE_URL` / `QG_COLLECTOR_APP_ID` / `QG_COLLECTOR_APP_PRIVATE_KEY` / 対象ごとの Ingest Token）は
[収集ランナーで計測する](collector.md#1-3-quality-gate-リポジトリの変数とシークレット) を参照してください。

以前 quality-gate 自身の計測に使っていたリポジトリ変数 `QG_RUNNER` / `QG_RUN_HEAVY_ON_GITHUB` とシークレット `QG_INGEST_TOKEN` は
使わなくなりました。設定済みなら削除して構いません（`QG_BASE_URL` は収集ランナーが使うため残します）。

## 4. 動作確認

[収集ランナーで計測する](collector.md#2-手動で実行する) の手動実行で、ジョブがセルフホストランナーで動くことを確かめます。

## ランナーを止めるとき

セルフホストランナーが停止していると、計測ジョブはランナーの空きを待ったまま進みません。

**収集ランナーは GitHub ホストランナーへ退避できません。** 止めている間は対象の計測も止まり、定期実行が待機のまま残ります
（`concurrency` により待機は 1 つだけ。24 時間待つと GitHub が取り消します）。
ランナーを戻せば、次の定期実行で各ブランチ・PR のいまの先頭が計測されます。
長く止める場合は **Actions → collect → Disable workflow** で定期実行を止めておくと、待機中の実行が溜まりません。

> **注意**: セルフホストランナーは、ワークフローを動かせる人なら誰でもそのマシン上で任意のコードを実行できます。
> 収集ランナーは対象リポジトリのテストコードもこのマシンで実行するため、quality-gate リポジトリは private のままにしてください。
> リポジトリを公開する場合は、**Settings → Actions → General** で外部コントリビューターのワークフロー実行に承認を必須にしてください。
