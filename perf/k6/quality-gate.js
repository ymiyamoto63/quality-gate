// quality-gate 自身の負荷試験（M-03 / M-04 / M-05）。
// 仕様: docs/02-metrics-spec.md M-03
//
//   負荷モデル   constant-arrival-rate（到達率を固定し、VU 数は固定しない）
//   ウォームアップ 60 秒。phase=warmup のタグを付け、集計（phase=measure）から除く
//   計測時間     300 秒
//   到達率       合計 50 req/s（.quality-gate.yml の performance.arrival_rate_rps）
//
// 実行例（3 回実行し、それぞれの summary を送る。中央値は quality-gate が取る）:
//   k6 run --summary-export=reports/k6-summary-1.json perf/k6/quality-gate.js
//
// 環境変数:
//   QG_PERF_BASE_URL     計測対象（専有環境）の URL
//   QG_PERF_SESSION      参照 API に使うセッション Cookie（SESSION の値）
//   QG_PERF_RUN_ID       Run 詳細・状態取得に使う既存の Run ID
//   QG_PERF_INGEST_TOKEN 状態取得（Ingest API）に使うトークン
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.QG_PERF_BASE_URL;
const SESSION = __ENV.QG_PERF_SESSION;
const RUN_ID = __ENV.QG_PERF_RUN_ID;
const INGEST_TOKEN = __ENV.QG_PERF_INGEST_TOKEN;

const WARMUP = '60s';
const MEASURE = '300s';

/** 計測区間のシナリオ。rate の合計が到達率（50 req/s）になる。 */
function measured(exec, rate) {
  return {
    executor: 'constant-arrival-rate',
    exec,
    rate,
    timeUnit: '1s',
    duration: MEASURE,
    startTime: WARMUP,
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
      exec: 'dashboard',
      rate: 50,
      timeUnit: '1s',
      duration: WARMUP,
      preAllocatedVUs: 100,
      tags: { phase: 'warmup' },
    },
    // シナリオ名は .quality-gate.yml の performance.scenarios と一致させる
    dashboard: measured('dashboard', 20),
    'run-detail': measured('runDetail', 20),
    ingest: measured('ingestStatus', 10),
  },
  // k6 はしきい値を定義したタグ付きの部分指標だけを summary に出す。
  // 合否は quality-gate が判定するため、ここでは必ず満たす条件を書いて出力だけさせる。
  thresholds: {
    'http_req_duration{phase:measure}': ['p(95)>=0'],
    'http_reqs{phase:measure}': ['count>=0'],
    'http_req_failed{phase:measure}': ['rate>=0'],
    'http_req_duration{scenario:dashboard}': ['p(95)>=0'],
    'http_req_duration{scenario:run-detail}': ['p(95)>=0'],
    'http_req_duration{scenario:ingest}': ['p(95)>=0'],
  },
};

const sessionParams = { headers: { Cookie: `SESSION=${SESSION}` } };

export function dashboard() {
  const res = http.get(`${BASE_URL}/api/v1/dashboard`, sessionParams);
  check(res, { 'dashboard 200': (r) => r.status === 200 });
}

export function runDetail() {
  const res = http.get(`${BASE_URL}/api/v1/runs/${RUN_ID}`, sessionParams);
  check(res, { 'run detail 200': (r) => r.status === 200 });
}

export function ingestStatus() {
  const res = http.get(`${BASE_URL}/api/v1/runs/${RUN_ID}/status`, {
    headers: { Authorization: `Bearer ${INGEST_TOKEN}` },
  });
  check(res, { 'status 200': (r) => r.status === 200 });
}
