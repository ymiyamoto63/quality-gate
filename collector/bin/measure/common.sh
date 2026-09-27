# shellcheck shell=bash
# 複数の指標の計測で共用するもの（サーバの起動、画面のビルド、ツールの用意、合格ラインの読み取り）。
# measure.sh が source する（単独では実行しない）。

# --- サーバの起動（M-03 / M-04 / M-08 で共用） ----------------------------------------
SERVERS=()
stop_servers() {
  local pid
  for pid in "${SERVERS[@]}"; do
    # setsid で起動したので、npx などの子プロセスごとプロセスグループで止める
    kill -- "-$pid" 2>/dev/null || true
  done
  SERVERS=()
}
trap stop_servers EXIT

# start_server <ログ> <コマンド...>。バックグラウンドで起動し、PID を SERVERS に積む
start_server() {
  local log=$1; shift
  setsid "$@" > "$log" 2>&1 < /dev/null &
  SERVERS+=($!)
}

# wait_http <URL> <秒>。応答が返る（HTTP のステータスは問わない）まで待つ
wait_http() {
  local url=$1 limit=$2 i
  for ((i = 0; i < limit; i++)); do
    curl -s -o /dev/null --max-time 2 "$url" && return 0
    sleep 1
  done
  return 1
}

# start_backend <指標> <ポート> <ログ> <待つ秒数> [JVM の引数...]。
# measure_backend の verify で出来た実行可能 jar を起動し、応答が返るまで待つ。失敗したら fail して 1 を返す
start_backend() {
  local metric=$1 port=$2 log=$3 limit=$4 jar
  shift 4
  jar=$(find "$SRC/$BACKEND_DIR/target" -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' ! -name '*-sources.jar' \
    ! -name '*-javadoc.jar' 2>/dev/null | head -n 1 || true)
  if [ -z "$jar" ]; then
    fail "$metric: バックエンドの jar がありません（ビルドに失敗しています）"; return 1
  fi
  start_server "$log" java "$@" -jar "$jar" --server.port="$port"
  if ! wait_http "http://127.0.0.1:${port}/" "$limit"; then
    tail -n 50 "$log" >&2
    stop_servers
    fail "$metric: バックエンドが起動しませんでした"; return 1
  fi
}

# --- 画面のビルド（M-08）。本番と同じビルド結果を使う ---------------------
FRONTEND_BUILD_OK=0
build_frontend() {
  group "フロントエンドのビルド（vite build）"
  if (cd "$SRC/$FRONTEND_DIR" && npx vite build); then
    FRONTEND_BUILD_OK=1
  fi
  endgroup
}

# prepare_tool <collector/ 以下のディレクトリ名> <コンテナのイメージに入っているディレクトリ> <作業ディレクトリ> [写すファイル...]
# 版を固定したツールを用意する。node_modules はコンテナのイメージに入っているものを使い、スクリプトや設定は collector/ から写す
prepare_tool() {
  local name=$1 prebuilt=$2 dir=$3 file
  shift 3
  (
    set -e
    rm -rf "$dir"
    mkdir -p "$dir"
    for file in "$@"; do cp "$COLLECTOR_DIR/$name/$file" "$dir/"; done
    ln -s "$prebuilt/node_modules" "$dir/node_modules"
  )
}

# --- 画面の起動（M-08） ---------------------------------------------------
# 対象の e2e（API のモック）は使わず、バックエンドの jar とビルドした画面を実際に起動する。
# 検査のスクリプトとツールの版は quality-gate 側のもの（collector/a11y）
A11Y_TOOL="$WORK/a11y"
APP_URL=

# Chromium もコンテナのイメージに入っている
prepare_a11y_tool() {
  prepare_tool a11y "$QG_A11Y_TOOL_DIR" "$A11Y_TOOL" scan.mjs
}

# start_app <指標>。バックエンド（A11Y_BACKEND_PORT があれば）と、ビルドした画面（vite preview）を起動する
start_app() {
  local metric=$1 front="$SRC/$FRONTEND_DIR" port=${A11Y_FRONTEND_PORT:-4173}
  [ "$FRONTEND_BUILD_OK" = 1 ] || { fail "$metric: フロントエンドのビルドに失敗しました"; return 1; }
  # バックエンド。measure_backend の verify で出来た実行可能 jar を使う
  if [ -n "${A11Y_BACKEND_PORT:-}" ]; then
    start_backend "$metric" "$A11Y_BACKEND_PORT" "$WORK/a11y-backend.log" "${A11Y_START_TIMEOUT:-120}" || return 1
  fi
  # /api の proxy は vite.config の server.proxy を引き継ぐ
  start_server "$WORK/a11y-frontend.log" bash -c \
    "cd \"\$1\" && exec npx vite preview --host 127.0.0.1 --port \"\$2\" --strictPort" _ "$front" "$port"
  if ! wait_http "http://127.0.0.1:${port}/" "${A11Y_START_TIMEOUT:-120}"; then
    tail -n 50 "$WORK/a11y-frontend.log" >&2
    stop_servers
    fail "$metric: フロントエンドが起動しませんでした"; return 1
  fi
  APP_URL="http://127.0.0.1:${port}"
}

# --- 合格ライン（*.gate.yml）で無効の指標 ------------------------------------------------------------
# 合格ラインで enabled: false にした指標の名前（1 行 1 件）。書いていない指標は有効（quality-gate の既定と同じ）。
# quality-gate（SnakeYAML、YAML 1.1）と同じく、引用符の無い no / off も false として読む。"false" のような文字列は有効のまま
disabled_metrics() {
  yq '.metrics // {} | to_entries | .[]
    | select((.value.enabled | style) != "double" and (.value.enabled | style) != "single"
      and (.value.enabled | tostring | test("^(false|False|FALSE|no|No|NO|off|Off|OFF)$")))
    | .key' < "$1"
}

# metric_enabled <合格ラインの指標名>（performance など）
metric_enabled() { ! grep -qx "$1" <<< "$DISABLED_METRICS"; }
