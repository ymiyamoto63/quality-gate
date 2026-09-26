#!/usr/bin/env bash
# 計測するコミットまでのファイルの移動・リネームを求め、JSON で標準出力に書く（指標仕様書 0.4）。
#
# quality-gate は、移動しただけのファイルの違反を新規・解消として扱わないためにこれを使う。
# 比較元（base）からの移動は直接の差分で、比較対象の Run（前回の計測）からの移動は履歴から求める。
# 比較対象の Run は quality-gate が判定のときに決めるため、ここでは既定の本数までの履歴を送る。
#
# 使い方: renames.sh <対象リポジトリ> <計測するコミット> [比較元のコミット]
# 出力:
#   {"head": "<コミット>",
#    "base": {"sha": "<比較元>", "renames": {"新しいパス": "移動前のパス"}},   比較元が無ければ持たない
#    "history": [{"sha": "<コミット>", "renames": {...}}, ...]}
#   history は計測するコミットから first-parent をたどった新しい順。renames はその親からの移動（無ければ持たない）
#
# 任意の環境変数:
#   QG_RENAME_HISTORY  たどるコミット数（既定: 1000）
set -euo pipefail

[ $# -ge 2 ] && [ $# -le 3 ] || { echo "使い方: renames.sh <対象リポジトリ> <計測するコミット> [比較元のコミット]" >&2; exit 2; }
SRC=$1
COMMIT=$2
BASE=${3:-}
LIMIT=${QG_RENAME_HISTORY:-1000}

# git の -z 出力（"R100\0移動前\0移動後\0" の繰り返し）を {"移動後": "移動前"} にする
PAIRS='def pairs: [range(0; (length - 2); 3) as $i
          | select(.[$i] | ltrimstr("\n") | startswith("R"))
          | {key: .[$i + 2], value: .[$i + 1]}] | from_entries;'

base_json=null
if [ -n "$BASE" ]; then
  base_json=$(git -C "$SRC" diff -M --diff-filter=R --name-status -z "$BASE" "$COMMIT" \
    | jq -Rs --arg sha "$BASE" "$PAIRS"' {sha: $sha, renames: (split("\u0000") | pairs)}')
fi

# 移動のあったコミットだけ（先頭に \x01 とコミット SHA）
by_commit=$(git -C "$SRC" log --first-parent --diff-merges=first-parent -M --diff-filter=R --name-status -z \
    --format='%x01%H' -n "$LIMIT" "$COMMIT" \
  | jq -Rs "$PAIRS"' split("\u0001")[1:] | map(split("\u0000") | {key: .[0], value: (.[1:] | pairs)}) | from_entries')

git -C "$SRC" rev-list --first-parent -n "$LIMIT" "$COMMIT" \
  | jq -R . | jq -s --arg head "$COMMIT" --argjson base "$base_json" --argjson renames "$by_commit" \
    '{head: $head}
     + (if $base == null then {} else {base: $base} end)
     + {history: map({sha: .} + (if ($renames[.] // {}) == {} then {} else {renames: $renames[.]} end))}'
