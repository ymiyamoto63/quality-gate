#!/usr/bin/env bash
# 計測済みの記録（段階 2）。定期実行が同じコミットを何度も計測しないようにする。
#
# 使い方:
#   state.sh key <owner/name> <branch> <PR 番号（無ければ空）> <commit>   記録のキーを出力する
#   state.sh check <key>                                                  ok / failed <回数> / new を出力する
#   state.sh record <key> ok|failed                                       結果を記録する
#
# 記録はランナーのマシン上のファイル（既定: ~/.local/state/quality-gate-collector/measured.tsv）。
# 1 行 1 件で「キー<TAB>結果<TAB>日時」を追記する。消えても、各ブランチ・PR の先頭を 1 回ずつ
# 計測し直すだけで済む（過去のコミットをさかのぼって計測することはない）。
#
# キーは「リポジトリ + 文脈（branch:<名前> / pr:<番号>）+ コミット」。
# 同じコミットでも、既定ブランチとしての計測と PR としての計測は比較元（base）が違うため別に扱う。
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

STATE_FILE=${QG_COLLECTOR_STATE:-$HOME/.local/state/quality-gate-collector/measured.tsv}

state_key() {
  local repository=$1 branch=$2 pr=$3 commit=$4 context
  [[ "$commit" =~ ^[0-9a-f]{40}$ ]] || die "コミットは 40 桁の SHA で指定してください: $commit"
  if [ -n "$pr" ]; then context="pr:$pr"; else context="branch:$branch"; fi
  printf '%s %s %s\n' "$repository" "$context" "$commit"
}

state_check() {
  local key=$1 failures
  [ -f "$STATE_FILE" ] || { echo new; return; }
  if awk -F'\t' -v k="$key" '$1 == k && $2 == "ok" { found = 1 } END { exit !found }' "$STATE_FILE"; then
    echo ok
    return
  fi
  failures=$(awk -F'\t' -v k="$key" '$1 == k && $2 == "failed" { n++ } END { print n + 0 }' "$STATE_FILE")
  if [ "$failures" -gt 0 ]; then echo "failed $failures"; else echo new; fi
}

state_record() {
  local key=$1 result=$2
  case "$result" in ok|failed) ;; *) die "結果は ok か failed で指定してください: $result" ;; esac
  mkdir -p "$(dirname "$STATE_FILE")"
  # 複数のジョブが同時に書いても行が混ざらないようにロックする
  (
    flock 9
    printf '%s\t%s\t%s\n' "$key" "$result" "$(date -u +%FT%TZ)" >> "$STATE_FILE"
  ) 9>> "$STATE_FILE.lock"
  log "記録しました: $key → $result"
}

# source されたときは関数だけを提供する
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  command=${1:-}
  shift || true
  case "$command" in
    key) [ $# -eq 4 ] || die "使い方: state.sh key <owner/name> <branch> <PR 番号> <commit>"; state_key "$@" ;;
    check) [ $# -eq 1 ] || die "使い方: state.sh check <key>"; state_check "$1" ;;
    record) [ $# -eq 2 ] || die "使い方: state.sh record <key> ok|failed"; state_record "$1" "$2" ;;
    *) die "使い方: state.sh key|check|record ..." ;;
  esac
fi
