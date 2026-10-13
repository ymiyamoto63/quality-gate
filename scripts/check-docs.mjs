#!/usr/bin/env node
// ドキュメントの整合性検査（PR の CI の「ドキュメントの整合性」ジョブで動かす）。
//
// README.md・docs/・.env.example は、コードにある指標の名前や環境変数を書き写している。
// 書き写した側だけが古くなるのを防ぐため、次の 2 つを突き合わせる。依存パッケージは使わない。
//
//   1. 指標の ID と名前: 正は MetricCatalog.java。README.md の品質基準の表、docs/metrics.md の 1 章の表、
//      docs/overview.html の指標の表に、すべての指標が ID と名前の組で載っていること
//   2. 環境変数（QG_*）: application.yml が読む QG_* はすべて docs/operations.md に書かれていること。
//      ドキュメントに出てくる QG_* は application.yml にあるか、下の ALLOWED_OUTSIDE_APP に載っていること
//
// 使い方: node scripts/check-docs.mjs（リポジトリのどこから実行してもよい）

import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const CATALOG = 'backend/src/main/java/com/qualitygate/domain/metric/MetricCatalog.java';
const APPLICATION_YML = 'backend/src/main/resources/application.yml';

// アプリ（application.yml）は読まないが、ドキュメントに出てきてよい QG_*。
// 値は、その名前が実際に使われている（または廃止を知らせている）ファイル。名前がそこから消えたら検査が失敗し、
// この一覧が古くなったことに気づける
const ALLOWED_OUTSIDE_APP = {
  // 収集ランナー（collector/）が読む
  QG_TARGET: 'collector/bin/lib.sh',
  QG_BRANCH: 'collector/bin/fetch.sh',
  QG_COMMIT: 'collector/bin/fetch.sh',
  QG_COLLECTOR_BASE_IMAGE: 'collector/bin/measure-isolated.sh',
  QG_COLLECTOR_MEMORY: 'collector/bin/measure-isolated.sh',
  QG_COLLECTOR_DOCKER_ARGS: 'collector/bin/measure-isolated.sh',
  QG_A11Y_TOOL_DIR: 'collector/bin/measure.sh',
  QG_COMPLEXITY_TOOL_DIR: 'collector/bin/measure.sh',
  // 収集ワークフロー（collect.yml）の Actions の Variables / Secrets
  QG_COLLECTOR_APP_ID: '.github/workflows/collect.yml',
  QG_COLLECTOR_APP_PRIVATE_KEY: '.github/workflows/collect.yml',
  // フロントエンドの E2E・アクセシビリティ検査（Playwright）
  QG_E2E_BASE_URL: 'frontend/playwright.config.ts',
  QG_E2E_CHROMIUM: 'frontend/playwright.config.ts',
  // 廃止した設定。残っていると起動を止めるため、運用の手引きで名前を挙げている
  QG_SKIPPED_TESTS_INCREASE_MAX: 'backend/src/main/java/com/qualitygate/platform/config/RemovedSettings.java',
};

const errors = [];
const read = (path) => readFileSync(join(ROOT, path), 'utf8');

// ---- 1. 指標の ID と名前 ----

function loadCatalog() {
  const src = read(CATALOG);
  const ids = new Map(); // 定数名 → M-xx
  for (const m of src.matchAll(/\b(M_[A-Z0-9_]+)\s*=\s*"(M-\d+)"/g)) ids.set(m[1], m[2]);
  const metrics = [];
  for (const m of src.matchAll(/new MetricDefinition\(\s*(M_[A-Z0-9_]+)\s*,\s*"([^"]+)"/g)) {
    const id = ids.get(m[1]);
    if (!id) errors.push(`${CATALOG}: 定数 ${m[1]} の ID（"M-xx"）が見つかりません`);
    else metrics.push({ id, name: m[2] });
  }
  if (metrics.length === 0) errors.push(`${CATALOG}: 指標の定義（new MetricDefinition(...)）を読み取れませんでした`);
  return metrics;
}

/** 表の行（ID → 指標の名前のセル）を突き合わせる */
function checkTable(file, label, rows, metrics) {
  if (rows.size === 0) {
    errors.push(`${file}: ${label}が見つかりません（検査スクリプトの読み取り方を見直してください）`);
    return;
  }
  for (const { id, name } of metrics) {
    const cell = rows.get(id);
    if (cell === undefined) errors.push(`${file}: ${label}に ${id}（${name}）の行がありません`);
    else if (!cell.includes(name)) {
      errors.push(`${file}: ${label}の ${id} の指標名「${cell}」に、MetricCatalog の名前「${name}」が含まれていません`);
    }
  }
  const known = new Set(metrics.map((m) => m.id));
  for (const id of rows.keys()) {
    if (!known.has(id)) errors.push(`${file}: ${label}の ${id} は MetricCatalog にありません`);
  }
}

function section(text, startPattern, endPattern, file, label) {
  const start = text.search(startPattern);
  if (start < 0) {
    errors.push(`${file}: ${label}が見つかりません（検査スクリプトの読み取り方を見直してください）`);
    return '';
  }
  const rest = text.slice(start + 1);
  const end = rest.search(endPattern);
  return end < 0 ? rest : rest.slice(0, end);
}

function checkMetrics() {
  const metrics = loadCatalog();

  // README.md: 品質基準の表に「名前（M-xx）」の形で書く
  const readme = section(read('README.md'), /^## 品質基準/m, /^## /m, 'README.md', '「品質基準（指標）」の節');
  for (const { id, name } of metrics) {
    if (!readme.includes(`${name}（${id}）`)) {
      errors.push(`README.md: 品質基準の表に「${name}（${id}）」がありません（MetricCatalog の名前と ID の組で書いてください）`);
    }
  }

  // docs/metrics.md: 1 章の表の 3 列目が指標名
  const metricsMd = section(read('docs/metrics.md'), /^## 1\. /m, /^## /m, 'docs/metrics.md', '1 章（指標の一覧）');
  const mdRows = new Map();
  for (const m of metricsMd.matchAll(/^\|\s*(M-\d+)\s*\|[^|]*\|([^|]*)\|/gm)) mdRows.set(m[1], m[2].trim());
  checkTable('docs/metrics.md', '1 章の表', mdRows, metrics);

  // docs/overview.html: <section id="metrics"> の表の 3 列目が指標名
  const overview = section(read('docs/overview.html'), /<section id="metrics">/, /<\/section>/, 'docs/overview.html', '指標の節（<section id="metrics">）');
  const htmlRows = new Map();
  for (const m of overview.matchAll(/<tr><td class="id">(M-\d+)<\/td><td>[^<]*<\/td><td>([^<]*)<\/td>/g)) htmlRows.set(m[1], m[2].trim());
  checkTable('docs/overview.html', '指標の表', htmlRows, metrics);
}

// ---- 2. 環境変数（QG_*） ----

function docFiles() {
  const files = ['README.md', '.env.example'];
  const walk = (dir) => {
    for (const e of readdirSync(join(ROOT, dir), { withFileTypes: true })) {
      const path = join(dir, e.name);
      if (e.isDirectory()) walk(path);
      else if (/\.(md|html)$/.test(e.name)) files.push(path);
    }
  };
  walk('docs');
  return files;
}

function checkEnvVars() {
  const appVars = new Set([...read(APPLICATION_YML).matchAll(/\$\{(QG_[A-Z0-9_]+)/g)].map((m) => m[1]));
  if (appVars.size === 0) errors.push(`${APPLICATION_YML}: QG_* の環境変数を読み取れませんでした`);

  const operations = read('docs/operations.md');
  for (const v of [...appVars].sort()) {
    if (!new RegExp(`\\b${v}\\b`).test(operations)) {
      errors.push(`docs/operations.md: ${v}（application.yml が読む）の説明がありません`);
    }
  }

  for (const [v, file] of Object.entries(ALLOWED_OUTSIDE_APP)) {
    if (!new RegExp(`\\b${v}\\b`).test(read(file))) {
      errors.push(`scripts/check-docs.mjs: 許可リストの ${v} が ${file} にありません。一覧を見直してください`);
    }
  }

  for (const file of docFiles()) {
    const lines = read(file).split('\n');
    lines.forEach((line, i) => {
      // 末尾の _ は除く（「QG_CRITICAL_/HIGH_...」のような省略も名前の誤りとして拾う）
      for (const m of line.matchAll(/\bQG_[A-Z0-9_]+/g)) {
        const v = m[0].replace(/_+$/, '');
        if (!appVars.has(v) && !(v in ALLOWED_OUTSIDE_APP)) {
          errors.push(`${file}:${i + 1}: ${v} は application.yml にも許可リスト（scripts/check-docs.mjs）にもありません`);
        }
      }
    });
  }
}

checkMetrics();
checkEnvVars();

if (errors.length > 0) {
  console.error(`ドキュメントの整合性検査: ${errors.length} 件の不一致があります\n`);
  for (const e of errors) console.error(`- ${e}`);
  console.error('\nドキュメント（またはコード）を直して揃えてください。');
  process.exit(1);
}
console.log('ドキュメントの整合性検査: 不一致はありません');
