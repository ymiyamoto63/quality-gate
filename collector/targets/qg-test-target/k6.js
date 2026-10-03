// qg-test-target の負荷試験（M-03 / M-04）。収集ランナーの measure.sh が実行する（QG_TARGET=qg-test-target）
// 仕様: docs/metrics.md M-03、手順: docs/operations.md 4.4
//
//   負荷モデル   constant-arrival-rate（到達率を固定し、VU 数は固定しない）
//   ウォームアップ 60 秒。phase=warmup のタグを付け、集計（phase=measure）から除く
//   計測時間     300 秒
//   到達率       合計 50 req/s（quality-gate の QG_ARRIVAL_RATE_RPS と一致させる）
//
// 対象は API だけ（静的アセットは含めない）。バックエンドの jar を直接叩く。
// すべて GET で、シードデータがある限り 2xx しか返さない。負荷中にデータは変わらない。
//
// 環境変数（measure.sh が渡す）:
//   PERF_BASE_URL          バックエンドの URL（例: http://127.0.0.1:8080）
//   PERF_WARMUP_SECONDS    ウォームアップの秒数（既定: 60）
//   PERF_DURATION_SECONDS  計測の秒数（既定: 300）
//   PERF_SUMMARY           summary の出力先（JSON）
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.PERF_BASE_URL;
const WARMUP = Number(__ENV.PERF_WARMUP_SECONDS || 60);
const MEASURE = Number(__ENV.PERF_DURATION_SECONDS || 300);

/** 計測区間のシナリオ。rate の合計が到達率（50 req/s）になる。 */
function measured(exec, rate) {
  return {
    executor: 'constant-arrival-rate',
    exec,
    rate,
    timeUnit: '1s',
    duration: `${MEASURE}s`,
    startTime: `${WARMUP}s`,
    preAllocatedVUs: rate * 2,
    maxVUs: rate * 10,
    tags: { phase: 'measure' },
  };
}

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    warmup: {
      executor: 'constant-arrival-rate',
      exec: 'mixed',
      rate: 50,
      timeUnit: '1s',
      duration: `${WARMUP}s`,
      preAllocatedVUs: 100,
      tags: { phase: 'warmup' },
    },
    // シナリオ名は quality-gate の QG_PERF_SCENARIOS（list,filter,detail）と一致させる
    list: measured('list', 20),
    filter: measured('filter', 15),
    detail: measured('detail', 15),
  },
  // k6 はしきい値を定義したタグ付きの部分指標だけを summary に出す。
  // 合否は quality-gate が判定するため、ここでは必ず満たす条件を書いて出力だけさせる。
  thresholds: {
    'http_req_duration{phase:measure}': ['p(95)>=0'],
    'http_reqs{phase:measure}': ['count>=0'],
    'http_req_failed{phase:measure}': ['rate>=0'],
    'http_req_duration{scenario:list}': ['p(95)>=0'],
    'http_req_duration{scenario:filter}': ['p(95)>=0'],
    'http_req_duration{scenario:detail}': ['p(95)>=0'],
  },
};

const STATUSES = ['TODO', 'IN_PROGRESS', 'DONE'];
// 一致件数が違う入力を混ぜる（英語・日本語・キーワードなし）
const KEYWORDS = ['report', 'fix', '会議', ''];

function pick(items) {
  return items[Math.floor(Math.random() * items.length)];
}

// シードの id を実行時に取得する（id を決め打ちしない）。
export function setup() {
  const res = http.get(`${BASE_URL}/api/tasks?size=100`);
  const ids = res.json('items').map((t) => t.id);
  if (ids.length === 0) {
    throw new Error('シードのタスクが 0 件のため負荷試験を中断する');
  }
  return { ids };
}

export function list() {
  const res = http.get(`${BASE_URL}/api/tasks`);
  check(res, { 'list 200': (r) => r.status === 200 });
}

export function filter() {
  // 非 ASCII は必ず符号化する（符号化しないと Tomcat が 400 で拒否する）
  const keyword = pick(KEYWORDS);
  const keywordParam = keyword === '' ? '' : `&keyword=${encodeURIComponent(keyword)}`;
  const res = http.get(`${BASE_URL}/api/tasks?status=${pick(STATUSES)}${keywordParam}&size=10`);
  check(res, { 'filter 200': (r) => r.status === 200 });
}

export function detail(data) {
  const res = http.get(`${BASE_URL}/api/tasks/${pick(data.ids)}`);
  check(res, { 'detail 200': (r) => r.status === 200 });
}

/** ウォームアップは 3 種の API を計測区間と同じ比率（20:15:15）で叩く。 */
export function mixed(data) {
  const r = Math.random() * 50;
  if (r < 20) list();
  else if (r < 35) filter();
  else detail(data);
}

// k6 の rate は「件数 ÷ テスト全体の時間」のため、ウォームアップの時間まで分母に入り、
// 計測区間の到達率より低く出る（60 秒 + 300 秒なら 5/6）。計測区間の件数 ÷ 計測秒数に直して出力する。
export function handleSummary(data) {
  const reqs = data.metrics['http_reqs{phase:measure}'];
  if (reqs && reqs.values) {
    reqs.values.rate = reqs.values.count / MEASURE;
  }
  return { [__ENV.PERF_SUMMARY || 'k6-summary.json']: JSON.stringify(data) };
}
