import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

// 409/403/429/503 是业务正确拒绝，不是 HTTP 失败，避免 http_req_failed 误报
http.setResponseCallback(http.expectedStatuses(200, 403, 409, 429, 503));

const success = new Counter('order_success');
const soldOut = new Counter('order_sold_out');
const duplicated = new Counter('order_duplicated');
const rateLimited = new Counter('order_rate_limited');
const serverError = new Counter('order_server_error');
const other = new Counter('order_other');

// 用法：
//   BASE=http://127.0.0.1:8080 SECKILL_ID=5 VUS=200 ITER=10000 \
//   k6 run loadtest/k6-seckill.js
export const options = {
  scenarios: {
    seckill: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 200),
      iterations: Number(__ENV.ITER || 10000),
      maxDuration: '180s',
    },
  },
};

function hash32(n) {
  n = (n ^ 61) ^ (n >>> 16);
  n = n + (n << 3);
  n = n ^ (n >>> 4);
  n = n * 0x27d4eb2d;
  n = n ^ (n >>> 15);
  return n >>> 0;
}

const BASE = __ENV.BASE || 'http://127.0.0.1:8080';
const SECKILL_ID = __ENV.SECKILL_ID || '1';

export default function () {
  // 每轮唯一 uid：等价于 VUS×ITER 个不同真实用户
  const uid = __VU * 1000000 + __ITER + 1;

  // 每轮唯一伪源 IP：必须用哈希打散，否则 VU 步长与 IP 段基数成倍数关系会碰撞，
  // 大量请求落到同几个 IP 上，直接撞死 IP 限流（20 次/秒），压测结论会被污染。
  const h1 = hash32(uid);
  const h2 = hash32(uid ^ 0x9e3779b9);
  const a = (h1 % 250) + 1;
  const b = ((h1 >>> 8) % 250) + 1;
  const c = ((h2 >>> 16) % 200) + 1;
  const ip = `10.${c}.${b}.${a}`;

  const res = http.post(`${BASE}/api/seckill/${SECKILL_ID}/order`, null, {
    headers: { 'X-User-Id': String(uid), 'X-Forwarded-For': ip },
    timeout: '10s',
  });

  if (res.status === 200) {
    success.add(1);
  } else {
    let code = '';
    try { code = JSON.parse(res.body).code || ''; } catch (e) {}
    if (res.status === 409 && code === 'SOLD_OUT') soldOut.add(1);
    else if (res.status === 409 && code === 'ALREADY_PARTICIPATED') duplicated.add(1);
    else if (res.status === 429) rateLimited.add(1);
    else if (res.status >= 500) serverError.add(1);
    else other.add(1);
  }

  check(res, {
    '合法状态码': (r) => [200, 403, 409, 429, 503].includes(r.status),
  });
}

export function handleSummary(data) {
  const m = (k) => (data.metrics[k] ? data.metrics[k].values.count : 0);
  const p = (k, q) => (data.metrics[k] ? data.metrics[k].values[q] : 0);
  const lines = [
    '',
    '===== 秒杀压测结果 =====',
    `VUs / 迭代        : ${__ENV.VUS || 200} / ${__ENV.ITER || 10000}`,
    `成功下单 (200)     : ${m('order_success')}`,
    `售罄   (409)      : ${m('order_sold_out')}`,
    `重复   (409)      : ${m('order_duplicated')}`,
    `限流   (429)      : ${m('order_rate_limited')}`,
    `服务错误 (5xx)     : ${m('order_server_error')}`,
    `其他              : ${m('order_other')}`,
    `请求总数          : ${m('http_reqs')}`,
    `吞吐 (req/s)      : ${(data.metrics.http_reqs.values.rate).toFixed(2)}`,
    `平均延迟 (ms)     : ${p('http_req_duration', 'avg').toFixed(2)}`,
    `P50 / P95 / P99   : ${p('http_req_duration', 'p(50)').toFixed(1)} / ${p('http_req_duration', 'p(95)').toFixed(1)} / ${p('http_req_duration', 'p(99)').toFixed(1)}`,
    `最大延迟 (ms)     : ${p('http_req_duration', 'max').toFixed(1)}`,
    '========================',
    '',
  ];
  return { stdout: lines.join('\n') };
}
