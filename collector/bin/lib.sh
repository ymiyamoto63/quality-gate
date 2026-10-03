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
# どれを使うかは環境変数 QG_TARGET で選ぶ（収集ワークフローは入力 target か Actions の Variable QG_TARGET を渡す）。
# 一度に計測する対象は 1 つだけ（quality-gate アプリの QG_REPOSITORY と一致させる）

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
}

# 計測しない指標（計測プロファイルの DISABLED_METRICS。quality-gate の QG_DISABLED_METRICS とそろえる）
# metric_enabled <指標 ID>（M-03 など）
metric_enabled() { ! grep -qw -- "$1" <<< "${DISABLED_METRICS:-}"; }

group() { echo "::group::$*"; }
endgroup() { echo "::endgroup::"; }
