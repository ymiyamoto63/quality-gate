// 収集ランナーの M-17（バンドルサイズ。参考値）。ビルドした画面のファイルサイズを JSON に書き出す。
//
// 使い方: node size.mjs <ビルド結果のディレクトリ> <出力 JSON>
// 出力: {"files": [{"path": "assets/index-abc.js", "bytes": 1234, "gzipBytes": 567}]}
//   path はビルド結果のディレクトリからの相対。gzip は最大圧縮（配信の設定に左右されない値にするため）。
//   ソースマップ（*.map）は配信されないため含めない。どのファイルを数えるかは quality-gate が決める。
// 依存パッケージを使わない（Node.js の標準モジュールだけで動く）。
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import { gzipSync, constants } from 'node:zlib'

const [dist, output] = process.argv.slice(2)
if (!dist || !output) {
  console.error('使い方: node size.mjs <ビルド結果のディレクトリ> <出力 JSON>')
  process.exit(2)
}

function* walk(dir) {
  for (const name of readdirSync(dir).sort()) {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) yield* walk(path)
    else yield path
  }
}

const files = []
for (const path of walk(dist)) {
  if (path.endsWith('.map')) continue
  const content = readFileSync(path)
  files.push({
    path: relative(dist, path).split(sep).join('/'),
    bytes: content.length,
    gzipBytes: gzipSync(content, { level: constants.Z_BEST_COMPRESSION }).length,
  })
}
if (files.length === 0) {
  console.error(`ビルド結果にファイルがありません: ${dist}`)
  process.exit(1)
}
writeFileSync(output, JSON.stringify({ files }, null, 2))
console.error(`${files.length} ファイルのサイズを書き出しました: ${output}`)
