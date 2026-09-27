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

# 計測プロファイル（計測対象は 1 つだけ）
PROFILE="$COLLECTOR_DIR/target/profile.env"

# 計測プロファイルと版の定義を読む
load_profile() {
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
