#!/usr/bin/env bash
# 比較元のコミットから計測するコミットまでのファイルの移動・リネームを求め、JSON で標準出力に書く（指標仕様書 0.4）。
#
# quality-gate は、移動しただけのファイルの違反を新規・解消として扱わないためにこれを使う。
# 追うのは比較元からの直接の差分だけ（比較元コミットの Run と比べるときに効く）。
#
# 使い方: renames.sh <対象リポジトリ> <計測するコミット> [比較元のコミット]
# 出力:
#   {"head": "<コミット>",
#    "base": {"sha": "<比較元>", "renames": {"新しいパス": "移動前のパス"}}}   比較元が無ければ base を持たない
set -euo pipefail

[ $# -ge 2 ] && [ $# -le 3 ] || { echo "使い方: renames.sh <対象リポジトリ> <計測するコミット> [比較元のコミット]" >&2; exit 2; }
SRC=$1
COMMIT=$2
BASE=${3:-}

if [ -z "$BASE" ]; then
  jq -n --arg head "$COMMIT" '{head: $head}'
  exit 0
fi

# git の -z 出力（"R100\0移動前\0移動後\0" の繰り返し）を {"移動後": "移動前"} にする
git -C "$SRC" diff -M --diff-filter=R --name-status -z "$BASE" "$COMMIT" \
  | jq -Rs --arg head "$COMMIT" --arg sha "$BASE" '
      split("\u0000") as $f
      | [range(0; ($f | length) - 2; 3) as $i
         | select($f[$i] | ltrimstr("\n") | startswith("R"))
         | {key: $f[$i + 2], value: $f[$i + 1]}]
      | {head: $head, base: {sha: $sha, renames: from_entries}}'
