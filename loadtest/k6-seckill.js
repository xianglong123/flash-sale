import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const success = new Counter('order_success');
const rejected = new Counter('order_rejected');

// 用法：
//   BASE=http://127.0.0.1:8080 SECKILL_ID=1 k6 run loadtest/k6-seckill.js
export const options = {
  scenarios: {
    seckill: {
      executor: 'shared-iterations',
      vus: 200,
      iterations: 10000,
      maxDuration: '90s',
    },
  },
};

const BASE = __ENV.BASE || 'http://127.0.0.1:8080';
const SECKILL_ID = __ENV.SECKILL_ID || '1';

export default function () {
  // 每个虚拟用户每次迭代一个唯一 uid，等价于 10000 个不同用户
  const uid = __VU * 100000 + __ITER + 1;
  const res = http.post(`${BASE}/api/seckill/${SECKILL_ID}/order`, null, {
    headers: { 'X-User-Id': String(uid) },
    timeout: '5s',
  });

  if (res.status === 200) {
    success.add(1);
  } else {
    rejected.add(1);
  }

  check(res, {
    'status is 200/409/403/429/503': (r) => [200, 409, 403, 429, 503].includes(r.status),
  });
}

export function handleSummary(data) {
  const ok = data.metrics.order_success ? data.metrics.order_success.values.count : 0;
  const no = data.metrics.order_rejected ? data.metrics.order_rejected.values.count : 0;
  const p99 = data.metrics.http_req_duration ? data.metrics.http_req_duration.values['p(99)'] : 0;
  return {
    stdout: `\n===== 秒杀压测结果 =====\n成功下单: ${ok}\n被拒绝:   ${no}\n合计请求: ${ok + no}\nP99 延迟: ${p99.toFixed(1)} ms\n========================\n`,
  };
}
