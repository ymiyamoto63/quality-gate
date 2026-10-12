# shellcheck shell=bash
# 収集ランナーのスクリプトが共通で使う関数。source して使う。

COLLECTOR_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

log() { echo "[collector] $*" >&2; }
warn() { echo "::warning::$*" >&2; }
die() { echo "::error::$*" >&2; exit 1; }

# KEY=VALUE 形式のファイルを読み、環境変数に設定する。
# シェルとして実行しない（計測プロファイルや meta.env は source しない）。
# 値を囲む引用符は 1 組だけ外す。空行と # で始まる行は読み飛ばす。
load_env() {
  local file=$1 line key value
  [ -f "$file" ] || die "ファイルがありません: $file"
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue ;; esac
    [[ "$line" =~ ^([A-Z][A-Z0-9_]*)=(.*)$ ]] || die "解釈できない行です（$file）: $line"
    key=${BASH_REMATCH[1]}
    value=${BASH_REMATCH[2]}
    if [[ "$value" =~ ^\"(.*)\"$ ]] || [[ "$value" =~ ^\'(.*)\'$ ]]; then
      value=${BASH_REMATCH[1]}
    fi
    printf -v "$key" '%s' "$value"
    export "${key?}"
  done < "$file"
}

# 計測対象ごとの設定（計測プロファイルと k6 のシナリオ）は collector/targets/<名前>/ に置く。
# どれを使うかは環境変数 QG_TARGET で選ぶ。収集ワークフローでは fetch.sh が quality-gate の QG_REPOSITORY から
# 計測プロファイルを探して meta.env に書き、measure / submit はそれを読む（Variable などで指定しなくてよい）。
# 一度に計測する対象は 1 つだけ（quality-gate アプリの QG_REPOSITORY と一致させる）

# quality-gate の設定（GET /api/v1/collector/settings）を読み、SETTINGS（JSON）と APP_REPOSITORY（QG_REPOSITORY）を決める。
# QG_BASE_URL と QG_INGEST_TOKEN が要る
read_app_settings() {
  SETTINGS=$(curl -sS --retry 3 --fail-with-body "${QG_BASE_URL%/}/api/v1/collector/settings" \
    -H "Authorization: Bearer ${QG_INGEST_TOKEN}") || die "quality-gate の設定を読めませんでした: ${QG_BASE_URL}"
  APP_REPOSITORY=$(jq -r '.repository' <<< "$SETTINGS")
  [[ "$APP_REPOSITORY" =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$ ]] \
    || die "quality-gate の QG_REPOSITORY を解釈できません: $APP_REPOSITORY"
}

# QG_REPOSITORY が <リポジトリ> の計測プロファイルのディレクトリ名を出す。0 件か 2 件以上なら止める
# find_target <owner/name>
find_target() {
  local repository=$1 profile found=()
  for profile in "$COLLECTOR_DIR"/targets/*/profile.env; do
    [ -f "$profile" ] || continue
    if [ "$( (load_env "$profile"; echo "${QG_REPOSITORY:-}") )" = "$repository" ]; then
      found+=("$(basename "$(dirname "$profile")")")
    fi
  done
  [ ${#found[@]} -gt 0 ] || die "QG_REPOSITORY=$repository の計測プロファイルがありません（collector/targets/<名前>/profile.env を作る）"
  [ ${#found[@]} -eq 1 ] || die "QG_REPOSITORY=$repository の計測プロファイルが複数あります: ${found[*]}"
  echo "${found[0]}"
}

# 計測プロファイルと版の定義を読む。TARGET_DIR（対象の設定のディレクトリ）と PROFILE を決める
load_profile() {
  [ -n "${QG_TARGET:-}" ] || die "QG_TARGET が未設定です（collector/targets/ のディレクトリ名を指定する）"
  [[ "$QG_TARGET" =~ ^[A-Za-z0-9._-]+$ ]] && [ "$QG_TARGET" != . ] && [ "$QG_TARGET" != .. ] \
    || die "QG_TARGET は collector/targets/ のディレクトリ名で指定してください: $QG_TARGET"
  TARGET_DIR="$COLLECTOR_DIR/targets/$QG_TARGET"
  PROFILE="$TARGET_DIR/profile.env"
  [ -f "$PROFILE" ] || die "計測プロファイルがありません: $PROFILE"
  load_env "$COLLECTOR_DIR/versions.env"
  load_env "$PROFILE"
  [[ "${QG_REPOSITORY:-}" =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$ ]] \
    || die "計測プロファイルの QG_REPOSITORY は owner/name の形式で指定してください: ${QG_REPOSITORY:-}"
  # 計測しない指標はアプリの QG_DISABLED_METRICS だけで決める（2 か所に書くと食い違って計測エラーになるため廃止した）
  [ -z "${DISABLED_METRICS:-}" ] \
    || die "計測プロファイルの DISABLED_METRICS は廃止しました。止める指標は quality-gate の QG_DISABLED_METRICS に書いてください（$PROFILE）"
}

# 計測しない指標。quality-gate の QG_DISABLED_METRICS（fetch.sh が meta.env に書く。空白区切り）
# metric_enabled <指標 ID>（M-03 など）
metric_enabled() { ! grep -qw -- "$1" <<< "${QG_DISABLED_METRICS:-}"; }

# measure ジョブを実行するランナー（runs-on のラベル）を出す（DD-28）。meta.env と計測プロファイルを読んだ後に呼ぶ。
#   - Variables の QG_COLLECTOR_RUNNER があれば、それ
#   - 性能（M-03 / M-04）を計測するなら self-hosted（専有のマシンでないと値を比べられない。DD-12）
#   - それ以外は GitHub ホストランナー（QG_HOSTED_RUNNER。collect.yml の fetch / submit の runs-on と同じ値）
QG_HOSTED_RUNNER=ubuntu-24.04
measure_runner() {
  if [ -n "${QG_COLLECTOR_RUNNER:-}" ]; then
    [[ "$QG_COLLECTOR_RUNNER" =~ ^[A-Za-z0-9._-]+$ ]] \
      || die "QG_COLLECTOR_RUNNER はランナーのラベル 1 つで指定してください（例: self-hosted / ubuntu-24.04）: $QG_COLLECTOR_RUNNER"
    log "measure は QG_COLLECTOR_RUNNER（Variables）のランナーで実行します: $QG_COLLECTOR_RUNNER"
    echo "$QG_COLLECTOR_RUNNER"
  elif [ -n "${PERF_SCRIPT:-}" ] && metric_enabled M-03; then
    log "性能（M-03 / M-04）を計測するため、measure はセルフホストランナーで実行します"
    log "セルフホストランナーが登録されていないと、measure は割り当てを待ち続け、24 時間で取り消されます"
    echo self-hosted
  else
    log "measure は GitHub ホストランナー（$QG_HOSTED_RUNNER）で実行します"
    echo "$QG_HOSTED_RUNNER"
  fi
}

# M-08 で検査する画面（空白区切り）。quality-gate の QG_ACCESSIBILITY_PAGES（fetch.sh が meta.env に書く）と、
# 計測プロファイルの A11Y_PAGES の和（重複は除く）。
# アプリの値のうち `:` で始まる区切りを含むもの（/tasks/:id など）は照合用のパターンで開けないため除く。
# その画面は、具体的なパス（/tasks/1 など）を A11Y_PAGES に書いて検査する
a11y_pages() {
  local page out=" "
  local -a app profile
  read -ra app <<< "${QG_ACCESSIBILITY_PAGES:-}"
  read -ra profile <<< "${A11Y_PAGES:-}"
  for page in "${app[@]}"; do
    [[ "$page" == */:* ]] || [[ "$out" == *" $page "* ]] || out+="$page "
  done
  for page in "${profile[@]}"; do
    [[ "$out" == *" $page "* ]] || out+="$page "
  done
  out=${out# }
  echo "${out% }"
}

group() { echo "::group::$*"; }
endgroup() { echo "::endgroup::"; }
