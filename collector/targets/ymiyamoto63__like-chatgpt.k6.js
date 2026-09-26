// like-chatgpt の負荷試験（M-03 / M-04 / M-05）。収集ランナーの measure.sh が実行する。
// 仕様: docs/spec/02-metrics-spec.md M-03、手順: docs/operations/collector.md 8 章
//
//   負荷モデル   constant-arrival-rate（到達率を固定し、VU 数は固定しない）
//   ウォームアップ 60 秒。phase=warmup のタグを付け、集計（phase=measure）から除く
//   計測時間     300 秒
//   到達率       合計 50 req/s（合格ライン（*.gate.yml）の performance.arrival_rate_rps と一致させる）
//
// 対象は API だけ（静的アセットは含めない）。バックエンドの jar を直接叩く。
// like-chatgpt の API は外部のサービスを呼ばず、メモリ上のデータだけで応答する。
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
    // シナリオ名は合格ライン（*.gate.yml）の performance.scenarios と一致させる
    chat: measured('chat', 25),
    suggest: measured('suggest', 15),
    monitoring: measured('monitoring', 10),
  },
  // k6 はしきい値を定義したタグ付きの部分指標だけを summary に出す。
  // 合否は quality-gate が判定するため、ここでは必ず満たす条件を書いて出力だけさせる。
  thresholds: {
    'http_req_duration{phase:measure}': ['p(95)>=0'],
    'http_reqs{phase:measure}': ['count>=0'],
    'http_req_failed{phase:measure}': ['rate>=0'],
    'http_req_duration{scenario:chat}': ['p(95)>=0'],
    'http_req_duration{scenario:suggest}': ['p(95)>=0'],
    'http_req_duration{scenario:monitoring}': ['p(95)>=0'],
  },
};

const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };

// 応答の組み立て方が違う入力を混ぜる（キーワード照合・複数ターンの流れ・FAQ・該当なし）
const MESSAGES = [
  '担当者別の件数を教えて',
  'カテゴリ別の内訳',
  '日別の推移',
  'サマリーを見せて',
  'ダッシュボード',
  '新規問い合わせ',
  'CPU 使用率が高い原因を教えて',
  '請求書の再発行方法',
  'こんにちは',
];
const SUGGEST_INPUTS = ['担', '担当', 'カテ', '請求', '新規', ''];

function pick(items) {
  return items[Math.floor(Math.random() * items.length)];
}

export function chat() {
  const res = http.post(`${BASE_URL}/api/chat`, JSON.stringify({ message: pick(MESSAGES) }), JSON_HEADERS);
  check(res, { 'chat 200': (r) => r.status === 200 });
}

export function suggest() {
  const res = http.post(`${BASE_URL}/api/suggest`, JSON.stringify({ text: pick(SUGGEST_INPUTS) }), JSON_HEADERS);
  check(res, { 'suggest 200': (r) => r.status === 200 });
}

export function monitoring() {
  const res = http.get(`${BASE_URL}/api/monitoring/snapshot`);
  check(res, { 'monitoring 200': (r) => r.status === 200 });
}

/** ウォームアップは 3 つの API を計測区間と同じ比率で叩く。 */
export function mixed() {
  const r = Math.random() * 50;
  if (r < 25) chat();
  else if (r < 40) suggest();
  else monitoring();
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
