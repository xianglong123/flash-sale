# flash-sale · 秒杀系统 需求文档

| 项 | 值 |
| --- | --- |
| 项目名 | flash-sale |
| 语言/框架 | Java 17 + Spring Boot 3.2.x |
| 存储 | MariaDB 10.x 或 MySQL 5.7（按服务器可用源选择）+ Redis 5.x |
| 压测 | k6（脚本化，可纳入仓库） |
| 部署目标 | 云主机 CentOS 7 / 2 核 1.7G / 无 Docker |
| 前端 | 单页 HTML + 原生 JS，无构建、无框架 |
| 文档版本 | v2（相对 v1 增加管理端、展示页、部署与环境约束） |
| 本文档的作用 | 既是开发需求，也是 AI 编码的约束合同和验收标准 |

> **v2 变更摘要**
> 1. 增加管理端（设库存、开/停活动）与展示页（实时售卖情况）
> 2. 管理端必须鉴权（公网暴露，不鉴权等于谁都能改库存）
> 3. 库存调整规则：进行中只允许增加，禁止减少
> 4. 并发验收拆成「压测环境（本地）」与「演示环境（服务器）」
> 5. 新增第 12 节：部署形态、内存预算、swap 要求

---

## 0. 交付计划（分批，禁止一次全做）

| 阶段 | 范围 | 验收门槛 |
| --- | --- | --- |
| P0 | 活动查询 + 下单主链路（Redis 预扣 + DB 落库）+ 幂等 + 限流 + **管理端 + 展示页** | 1000 库存 / 10000 并发不超卖不少卖（本地压测）；服务器上功能全部可用 |
| P1 | 订单状态机 + 超时未支付回补 | 回补不产生负库存、不产生重复订单 |
| P2 | 压测报告 + 热点优化 + 故障演练 | P99 达标、依赖不可用时行为正确 |

**P0 不通过验收，不允许开始 P1。** 每阶段结束提交一次验收报告。

---

## 1. 目标与禁止项

### 1.1 一句话目标

在 2000 QPS 的瞬时压力下，保证 1000 件库存**精确卖出 1000 单**，不超卖、不少卖、不重复下单。

### 1.2 业务优先级（冲突时的裁决顺序）

```
不超卖 > 不少卖 > 响应速度 > 功能完整
```

出现冲突时按此顺序裁决。**宁可少卖，不可超卖**——超卖涉及履约赔偿，少卖只是少赚。

### 1.3 禁止项（违反即视为验收不通过）

**架构类**

- 禁止使用 `synchronized`、`ReentrantLock`、`AtomicInteger` 等单机内存手段控制库存。多实例部署下必然失效，且掩盖真实问题。
- 禁止把库存数量缓存在 JVM 内存里作为判断依据（本地缓存只能用于展示，不能用于决策）。
- 禁止在数据库事务里循环重试 `insert` 撞主键来处理幂等。
- 禁止用 `select ... for update` 逐条锁行来做库存扣减（会打满连接池）。

**依赖类**

- 只允许引入以下依赖，其余一律不加：`spring-boot-starter-web`、`spring-boot-starter-data-jpa`、`spring-boot-starter-data-redis`、`spring-boot-starter-validation`、`spring-boot-starter-actuator`、`mysql-connector-j`、`lombok`、`springdoc-openapi`。
- **禁止**引入：消息队列（Kafka/RocketMQ/RabbitMQ）、分布式锁框架（Redisson）、限流框架（Sentinel）、缓存框架（Caffeine/Ehcache）。
- 需要的能力用 Redis 原生命令和 Spring 自带组件实现，不要找库。

**范围类**

- 禁止自行扩展功能：不做购物车、不做优惠券、不做支付对接（支付用 mock 接口）、不做用户注册登录体系（下单侧用 header 传 userId）。
- 禁止改动数据库表结构以外的任何配置文件默认值（连接池大小等改动必须在 PR 说明里写原因）。
- 禁止在 `entity` 层加入业务逻辑。

**前端类**

- 只允许纯 HTML + 原生 JS + `fetch`，**禁止引入任何前端框架与构建工具**（React / Vue / Vite / npm 一律不加）。
- 禁止前端内联大段业务判断逻辑（限流、库存判断必须由后端返回结果，前端只做展示）。
- 禁止在前端暴露 `admin` token 明文（见 7.4）。

### 1.4 明确不做

- 不做分布式事务
- 不做多机房
- 不做风控模型（只做简单的频率限制）
- 不做用户登录注册（管理端用固定 token，下单端用 header）
- 不做移动端适配（桌面浏览器即可）
- 不做支付真实对接（mock）

---

## 2. 业务规则

### 2.1 活动模型

| 项 | 值 |
| --- | --- |
| 单场库存 | 1000 件（可配置） |
| 活动时间窗 | 开始时间 ~ 结束时间，精确到秒 |
| 每人限购 | 1 件 |
| 单场持续时长 | 测试用 60 秒 |
| 价格 | 固定单价，无优惠计算 |

### 2.2 下单判定顺序（必须按此顺序，顺序错会导致无效请求打穿后端）

```
1. 活动是否存在         → 不存在：404
2. 是否在时间窗内       → 不在：返回「未开始」或「已结束」
3. 请求频率是否超限     → 超限：429
4. 用户是否已购买       → 已购买：返回「已参与」
5. 库存是否充足         → 不足：返回「已售罄」
6. 扣减库存（原子）
7. 落库订单
```

第 1~5 步必须在扣减之前完成，且全部走 Redis，不查数据库。

### 2.3 状态定义

活动状态：`NOT_STARTED` / `ONGOING` / `ENDED` / `SOLD_OUT`
订单状态：`PENDING_PAY` / `PAID` / `TIMEOUT_CANCELLED`

### 2.4 管理端规则（v2 新增）

管理端通过前端表单操作，后端提供接口。**所有管理端接口必须鉴权**（见 7.4）。

#### 2.4.1 可执行的操作

| 操作 | 允许的时机 | 规则 |
| --- | --- | --- |
| 创建活动（设库存、设时间窗） | 无限制 | `total_stock` 取值 1 ~ 100000，超出返回 400 |
| 调整库存 | **仅 `NOT_STARTED`** | 可增可减，同时更新 DB 与 Redis |
| 增加库存 | 允许 `NOT_STARTED` 与 `ONGOING` | Redis `INCRBY` + DB `available_stock += n`，两步顺序必须是**先 Redis 后 DB** |
| 减少库存 | **禁止**，任何时候都不允许 | 返回 400 `STOCK_DECREASE_FORBIDDEN` |
| 开始活动 | `NOT_STARTED` | 把时间窗改成 `now ~ now+duration`，并预热 Redis |
| 结束活动 | `ONGOING` | 状态置 `ENDED`，Redis 库存 key 保留不删 |
| 查看实时数据 | 任何时候 | 见 2.4.3 |

#### 2.4.2 为什么禁止减少库存

减少库存会让“已经卖出的数量”超过“库存”，一旦发生就是超卖。即使当前还没卖完，减库存也会让 `available_stock` 与已售数之和超过 `total_stock`，历史数据从此不自洽。

需要“少卖”就把活动结束掉，不要动库存数字。

#### 2.4.3 展示页要求

展示页（`/`）面向观众，只读，不需要鉴权，必须展示：

- 商品名、单价
- 库存进度条（已售 / 总量）
- 剩余数量（数字）
- 已售数量（数字）
- 最近 10 条成功订单（用户 ID 脱敏，只显示后 4 位 + 时间）
- 活动状态与倒计时

数据通过轮询获取，**轮询间隔 1500ms**，不允许用 WebSocket（复杂度不值得）。

管理页（`/admin`）必须展示：全部活动列表、当前库存、已售数、一键开始/结束。

---

## 3. 数据模型

### 3.1 MySQL 表

**item（商品）**

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| id | BIGINT | PK, auto |
| name | VARCHAR(64) | not null |
| price | DECIMAL(10,2) | not null |
| created_at | DATETIME | not null |

**seckill（活动）**

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| id | BIGINT | PK, auto |
| item_id | BIGINT | not null |
| total_stock | INT | not null |
| available_stock | INT | not null, >= 0 |
| start_time | DATETIME | not null |
| end_time | DATETIME | not null |
| version | INT | not null default 0（乐观锁用） |

**seckill_order（订单）**

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| id | BIGINT | PK, auto |
| order_no | VARCHAR(32) | **UNIQUE** |
| user_id | BIGINT | not null |
| seckill_id | BIGINT | not null |
| item_id | BIGINT | not null |
| amount | DECIMAL(10,2) | not null |
| status | VARCHAR(16) | not null |
| created_at | DATETIME | not null |
| paid_at | DATETIME | null |

**唯一索引（幂等的最后一道防线，必须建）**

```
UNIQUE KEY uk_user_seckill (user_id, seckill_id)
```

### 3.2 Redis 结构

| Key | 类型 | 内容 | TTL |
| --- | --- | --- | --- |
| `seckill:stock:{seckillId}` | String | 剩余库存数 | 活动结束后 24h |
| `seckill:user:{seckillId}` | Set | 已下单 user_id 集合 | 同上 |
| `seckill:meta:{seckillId}` | Hash | 活动起止时间、状态、总库存 | 同上 |
| `seckill:rate:{userId}:{seckillId}` | String | 计数器 | 1 秒 |
| `seckill:recent:{seckillId}` | List | 最近 10 条成功订单摘要（LPUSH + LTRIM） | 结束 + 24h |

> `seckill:recent` 只存脱敏摘要（`"****8821|时间戳"`），**不存完整用户信息**，且只在扣减成功后 LPUSH，用于展示页，不参与任何业务判断。

**规则：库存 key 不存在不等于库存为 0。** 活动开始前必须预热；预热失败时活动不可开启（返回「活动未就绪」而不是「已售罄」）。

---

## 4. 接口契约

### 4.1 查询活动

```
GET /api/seckill/{seckillId}
Header: 无（匿名可访问）

200 {
  "seckillId": 1,
  "itemName": "iPhone 17",
  "price": 5999.00,
  "totalStock": 1000,
  "availableStock": 1000,   // 展示用，允许 1 秒内不一致
  "status": "ONGOING",
  "startTime": "...",
  "endTime": "..."
}
404 { "code": "NOT_FOUND" }
```

### 4.2 下单

```
POST /api/seckill/{seckillId}/order
Header: X-User-Id: <long>   （必填，缺失返回 401）

200 { "orderNo": "...", "status": "PENDING_PAY", "amount": 5999.00 }
409 { "code": "ALREADY_PARTICIPATED" }   // 用户已买过
409 { "code": "SOLD_OUT" }               // 售罄
429 { "code": "RATE_LIMITED" }           // 频率超限
403 { "code": "NOT_IN_WINDOW", "status": "NOT_STARTED|ENDED" }
503 { "code": "SYSTEM_BUSY" }            // 系统保护性拒绝
500 { "code": "INTERNAL_ERROR", "traceId": "..." }
```

**响应体要求：**
- 任何情况下都要返回 JSON，不允许返回 HTML 错误页
- 必须包含 `code` 字段，机器可判
- 500 必须带 `traceId`，与日志中的 traceId 一致

### 4.3 支付（mock，P1 才做）

```
POST /api/order/{orderNo}/pay
Header: X-User-Id
200 { "status": "PAID" }
```

### 4.4 健康检查

```
GET /actuator/health  → 200 {"status":"UP"}
```

### 4.5 展示接口（v2 新增，匿名可访问）

```
GET /api/seckill/{seckillId}/stats

200 {
  "seckillId": 1,
  "status": "ONGOING",
  "totalStock": 1000,
  "availableStock": 873,
  "soldCount": 127,
  "startTime": "...",
  "endTime": "...",
  "serverTime": "...",
  "recentOrders": [
    { "userMasked": "****8821", "createdAt": "2026-11-23T18:20:01" }
  ]
}
```

- `soldCount` = `totalStock - availableStock`，从 Redis 实时算，不查 DB
- `recentOrders` 最多 10 条，来自 Redis List `seckill:recent:{id}`（LPUSH + LTRIM 保持 10 条）
- `userMasked` 只保留后 4 位，**禁止返回完整 user_id**

### 4.6 管理端接口（v2 新增，全部需要鉴权）

```
POST /api/admin/seckill
Header: X-Admin-Token: <token>
Body: { "itemName": "iPhone 17", "price": 5999.00, "totalStock": 1000, "durationSeconds": 60 }
201 { "seckillId": 1, "status": "NOT_STARTED" }

PUT /api/admin/seckill/{id}/stock
Header: X-Admin-Token
Body: { "totalStock": 1500 }
200 { "totalStock": 1500, "availableStock": 1500 }
400 { "code": "STOCK_DECREASE_FORBIDDEN" }   // 进行中减少库存

POST /api/admin/seckill/{id}/start
Header: X-Admin-Token
200 { "status": "ONGOING", "startTime": "...", "endTime": "..." }

POST /api/admin/seckill/{id}/end
Header: X-Admin-Token
200 { "status": "ENDED" }

GET /api/admin/seckill            // 列表
Header: X-Admin-Token
200 [ { "seckillId": 1, "status": "...", "totalStock": 1000, "availableStock": 873 } ]

401 { "code": "UNAUTHORIZED" }    // token 缺失或错误
```

**管理端接口的特殊约束：**

- 处 `401 UNAUTHORIZED` 外，不允许返回任何其他 401/403 语义
- 修改库存必须同时更新 Redis 与 DB，顺序为**先 Redis 后 DB**；DB 失败时记 `RECONCILE_REQUIRED`，不回滚 Redis
- 所有管理操作必须写审计日志：`[ADMIN] action=start seckillId=1 operator=tokenHash`

---

## 5. 并发与一致性（本文档的核心）

> 每一项都必须实现，不适用也要在验收报告里说明理由。

### 5.1 并发画像

**环境拆分（v2 新增）**：验收分两个环境，指标只对压测环境有效。

| 环境 | 机器 | 用途 | 并发验收 |
| --- | --- | --- | --- |
| **压测环境** | 本地 Mac（≥8 核） | 跑 C1~C4 并发验收 | **全部指标以此为准** |
| **演示环境** | 云主机 2 核 1.7G | 前端操作、功能演示、部署验证 | 只验功能正确，**不验并发** |

演示环境不承担以下任何指标，也不允许拿它跑 10000 并发：2 核 1.7G 的 CPU 会先于应用打满，测出的数字无效，且可能把机器打死（无 swap 时会 OOM）。

| 项 | 值（压测环境） |
| --- | --- |
| 目标峰值 QPS | 2000（下单接口） |
| 并发用户 | 10000，5 秒内到达 |
| 库存 | 1000 |
| 预期成功率 | 恰好 1000 单成功，其余全部明确拒绝（不是超时、不是 500） |
| 接口 P99 | < 200ms（2000 QPS 下） |
| 演示环境目标 | 50 并发以内功能正确，P99 不设指标 |

### 5.2 竞态点清单

| 资源 | 并发场景 | 期望行为 |
| --- | --- | --- |
| Redis 库存 | 多个请求同时扣同一库存 | 原子扣减，绝不出现负值 |
| 用户购买资格 | 同一用户并发发 10 个请求 | 只成功 1 个，其余返回已参与 |
| DB 订单行 | 同一 user+seckill 重复插入 | 唯一索引拦下，转成业务错误码，不抛 500 |
| 库存补偿（P1） | 超时取消与人工取消同时发生 | 只回补一次 |

### 5.3 幂等

- 下单接口必须幂等，幂等键 = `user_id + seckill_id`
- 期望语义：**首次成功返回订单号；重复请求返回同一订单号或 `ALREADY_PARTICIPATED`**，两种都可接受，但必须固定一种，不能随机
- 实现建议：先查 Redis Set，再由 DB 唯一索引兜底

### 5.4 扣减一致性策略（关键取舍）

采用**两阶段**：Redis 原子预扣 → 异步/同步落库。

明确规定的取舍：

- Redis 扣减成功但 DB 插入失败时：**不回滚 Redis 库存**，而是记录 `RECONCILE_REQUIRED` 异常单到日志和一张 `reconcile` 表，由补偿任务人工/自动处理。
- 理由：回滚会引入新的竞态（回滚与新的扣减交叉），且在秒杀场景下少卖 1 件可以接受，超卖不可接受。
- **禁止**实现"扣减失败就自动回滚库存"这类看起来完整、实际会制造超卖的方案。

### 5.5 一致性等级

- 库存最终一致，收敛时间上限：5 秒（DB 与 Redis 对齐）
- 活动详情页允许展示 1 秒前的库存数字，必须在页面文案注明"实时库存以提交结果为准"

### 5.6 限流

| 维度 | 限制 | 超限响应 |
| --- | --- | --- |
| 单用户单活动 | 1 次 / 秒 | 429 |
| 单 IP | 20 次 / 秒 | 429 |
| 全局下单 | 2500 QPS（保护阈值） | 503 |

限流判断必须在 Redis 里做，不能用内存计数器。

---

## 6. 失败与降级

| 依赖 | 超时 | 失败策略 | 是否降级 |
| --- | --- | --- | --- |
| Redis | 500ms | 直接拒绝下单（503），**不允许绕过 Redis 直连 DB 扣减** | 不降级 |
| MySQL | 3s | 下单失败，Redis 预扣不回滚（见 5.4），落异常单 | 不降级 |
| 活动元数据 | — | 缺失时返回「活动未就绪」，不返回「已售罄」 | — |

### 6.1 禁止静默失败

- 任何 `catch` 必须记录日志，包含 traceId、userId、seckillId、异常类型
- 禁止 `catch` 后返回 `200 + 空响应`
- 禁止吞掉 Redis 异常后继续走 DB 逻辑

### 6.2 降级开关

提供全局开关 `seckill.enabled`（配置项）：关闭时所有下单请求返回 503 `SYSTEM_BUSY`，用于紧急止血。

---

## 7. 安全

- 认证（下单侧）：无真实认证，`X-User-Id` 由调用方传入；**必须在代码注释中标注这是演示用，不具备真实安全性**
- 越权：下单时必须校验 `seckillId` 存在且时间窗有效，不校验 userId 归属（因为本身就是当前用户）
- 输入校验：`seckillId` 必须为正整数；`X-User-Id` 长度 ≤ 20 位数字；非法输入返回 400，不抛异常
- 敏感数据：不记录完整请求头；日志中 userId 只记录脱敏值（保留后 4 位）
- 禁止在 URL 里传输任何密钥

### 7.4 管理端鉴权（v2 新增，硬性要求）

管理端接口暴露在公网 443 端口上，**不鉴权等于任何人都能改库存、终止活动**。因此：

1. 管理端接口统一校验请求头 `X-Admin-Token`，值从环境变量 `ADMIN_TOKEN` 读取，**禁止硬编码在代码或配置文件里提交到仓库**。
2. token 比对必须使用**定长比较**（避免时序侧信道），不允许 `equals` 直接比。
3. 校验失败一律返回 `401 UNAUTHORIZED`，**不返回任何关于 token 格式的提示**。
4. 管理端接口必须额外做 IP 无关的速率限制：**每分钟最多 30 次**，超限返回 429。
5. 管理页 `/admin` 前端必须让用户手动输入 token（存 `sessionStorage`，不存 localStorage），刷新页面后需重新输入。
6. `ADMIN_TOKEN` 长度要求 ≥ 32 字符，部署时用 `openssl rand -hex 32` 生成。
7. **禁止把 token 打进任何日志**，审计日志里只记录 token 的 SHA-256 前 8 位。

> 若 `ADMIN_TOKEN` 未配置，应用启动失败（fail-fast），不允许以“无鉴权”状态运行。

---

## 8. 可观测性

### 8.1 日志（每次下单至少 5 条）

```
[REQ]  traceId userId seckillId
[CHECK] window=ok rate=ok participated=no stock=1000
[DEDUCT] result=success remain=999
[ORDER] orderNo=... status=PENDING_PAY costMs=45
[FAIL]  code=SOLD_OUT (或异常堆栈)
```

### 8.2 指标（必须通过 `/actuator/metrics` 暴露）

- `seckill.request.total`（计数器）
- `seckill.order.success`（计数器）
- `seckill.order.reject{reason}`（按 reason 分组：sold_out / duplicated / rate_limited / window）
- `seckill.deduct.latency`（Timer，毫秒）
- `seckill.reconcile.count`（异常单数量）

### 8.3 排查路径（写进 README）

1. 先看 `seckill.order.success` 总数，是否为 1000
2. 再看 `seckill.order.reject{reason}` 分布，判断是正常拒绝还是系统异常
3. 若 Redis 剩余库存 ≠ 1000 - 成功数，查 `RECONCILE_REQUIRED` 日志
4. 若 P99 超标，先查连接池等待时间，再查 Redis 慢查询

---

## 9. 验收标准（由人写，AI 只负责实现）

### 9.1 功能验收

| 编号 | 输入 | 期望 | 判定方式 |
| --- | --- | --- | --- |
| F1 | 活动进行中，库存 1000，单次下单 | 返回 200 + orderNo | curl |
| F2 | 同一用户连续下单 2 次 | 第二次 409 ALREADY_PARTICIPATED | curl |
| F3 | 库存为 0 时下单 | 409 SOLD_OUT | curl |
| F4 | 活动未开始 / 已结束 | 403 NOT_IN_WINDOW | curl |
| F5 | 缺少 X-User-Id | 401 | curl |
| F6 | seckillId 不存在 | 404 | curl |
| F7 | seckillId 传 `abc` | 400，不是 500 | curl |

### 9.2 并发验收（核心，必须用 k6 跑）

| 编号 | 场景 | 期望 | 判定 |
| --- | --- | --- | --- |
| C1 | 10000 个不同用户，5 秒内并发下单，库存 1000 | 成功数 **恰好 1000**；DB 订单数 **恰好 1000**；Redis 剩余库存 **恰好 0**；无负库存 | k6 报告 + SQL 核对 |
| C2 | 同一用户 100 并发下单 | 成功 **恰好 1** 单；DB 中该 user 订单 **恰好 1** 条 | k6 + SQL |
| C3 | 2000 QPS 持续 30 秒 | 无 500；P99 < 200ms | k6 报告 |
| C4 | 库存 1000，1500 并发 | 成功 1000，其余返回明确业务错误码（不是超时、不是 500） | k6 报告 |

**判定口径：**「成功数 = 1000」是精确断言，不是"约等于"。超卖 1 件、少卖 1 件都算不通过。

### 9.3 故障验收

| 编号 | 场景 | 期望 |
| --- | --- | --- |
| E1 | 停掉 Redis 再下单 | 503 SYSTEM_BUSY，不写入任何 DB 订单 |
| E2 | 先扣 Redis 再断开 MySQL（模拟） | 不产生超卖；产生 1 条 RECONCILE_REQUIRED 日志 |
| E3 | 配置 `seckill.enabled=false` | 所有下单返回 503 |

### 9.4 管理端与前端验收（v2 新增）

| 编号 | 场景 | 期望 | 判定 |
| --- | --- | --- | --- |
| A1 | 不带 token 调管理端接口 | 401 UNAUTHORIZED | curl |
| A2 | 带错误 token | 401，响应体不含任何 token 格式提示 | curl |
| A3 | 创建活动，库存设 0 或 100001 | 400 | curl |
| A4 | 活动进行中，调减库存接口 | 400 STOCK_DECREASE_FORBIDDEN，Redis 与 DB 数值均未变 | curl + SQL |
| A5 | 活动进行中，调增库存 +500 | Redis 库存 +500，DB `available_stock` +500，两个数字一致 | redis-cli + SQL |
| A6 | 管理页点「开始」 | 状态变 ONGOING，Redis 库存 key 存在 | 浏览器点击 |
| A7 | 展示页轮询 | 库存数字每 1.5 秒刷新，售罄后显示 SOLD_OUT | 浏览器观察 |

### 9.5 部署验收（v2 新增，在演示环境执行）

| 编号 | 场景 | 期望 |
| --- | --- | --- |
| D1 | `curl https://<域名>/actuator/health` | 200，`{"status":"UP"}` |
| D2 | 浏览器访问 `https://<域名>/` | 展示页正常渲染，轮询有数据 |
| D3 | 浏览器访问 `https://<域名>/admin` | 页面可打开，未输入 token 时接口返回 401 |
| D4 | 重启服务器后 | 应用自动启动（systemd enabled），健康检查恢复 UP |
| D5 | 压测 50 并发 10 秒 | 无 500，成功 + 售罄数量 = 库存总量 |

### 9.6 交付物清单

- `docs/requirement.md`（本文件）
- `README.md`：启动方式、压测方式、排查路径、部署步骤
- `loadtest/k6-seckill.js`：C1~C4 的可执行脚本
- `docs/report-P0.md`：含 k6 报告 + SQL 核对结果 + 前端截图
- 前端静态文件：`src/main/resources/static/index.html`、`static/admin.html`
- `deploy/` 目录：systemd unit、nginx 配置片段、部署脚本
- Swagger 可访问（`/swagger-ui.html`）

---

## 10. 对 AI 执行的约束

1. **先出设计说明再写代码。** 内容包含：扣减流程时序、Redis key 设计、幂等链路、异常路径。人确认后才写实现。
2. **一次只做一个阶段。** P0 未通过验收不允许开始 P1。
3. **不许扩大范围。** 发现其他问题只记录到 `docs/issues.md`，不顺手改。
4. **关键实现必须写注释说明"为什么"**，尤其是 5.4 的取舍。
5. **自检输出**：完成后逐条对照第 9 节，输出表格「编号 / 通过 / 证据（命令或日志）」。
6. **禁止自行增加表字段或索引**，需要时先在 `docs/issues.md` 提出。
7. **不确定就问。** 歧义处停下提问，不许猜。

---

## 11. 需要项目负责人确认的事

- [ ] ORM 用 JPA 还是 MyBatis（默认 JPA）
- [ ] 部署形态：jar + systemd + nginx 反代（不用 Docker），是否确认
- [ ] 数据库选型：MariaDB 10.x 还是 MySQL 5.7（按服务器可用源）
- [ ] 演示域名用哪个（443 由现有 nginx 接管）
- [ ] 内存预算与 swap 方案（见第 12 节），是否接受
- [ ] 管理端 token 由你生成并配置到服务器环境变量

---

## 12. 部署与环境约束（v2 新增）

### 12.1 目标机器现状（实测）

| 项 | 实际值 | 影响 |
| --- | --- | --- |
| 系统 | CentOS Linux 7（已 EOL） | 官方 yum 源已下线，装包需切 vault 源 |
| CPU | 2 核 | 只够演示，不够压测 |
| 内存 | 1.76 GB，可用 891 MB，**无 swap** | 必须加 swap，否则 OOM |
| 磁盘 | 40 GB，已用 8 GB | 充足 |
| Java | 未安装 | 用 tarball 装，不走 yum |
| MySQL / Redis | 均未安装 | 需安装并调小内存 |
| Docker | 未安装 | 不使用 Docker |
| nginx | 1.20.1，占用 80/443 | 复用，反代到 127.0.0.1:8080 |

### 12.2 部署形态（硬性约束）

- **不使用 Docker**，不使用 Docker Compose
- 应用以 jar 形式运行，由 `systemd` 托管，开机自启
- 应用只监听 `127.0.0.1:8080`，**不监听 0.0.0.0**，公网不可直连
- 对外只通过 nginx 80/443 暴露；80 强制跳 443
- 服务器上不安装 Maven、不编译代码；构建在本地完成，只上传 jar

### 12.3 内存预算（必须按此配置，否则 OOM）

| 组件 | 限制 | 配置方式 |
| --- | --- | --- |
| JVM | `-Xmx256m -Xms128m -XX:MaxMetaspaceSize=96m` | systemd `ExecStart` 参数 |
| MariaDB/MySQL | `innodb_buffer_pool_size=96M`，`max_connections=50` | my.cnf |
| Redis | `maxmemory 48mb`，`maxmemory-policy allkeys-lru` | redis.conf |
| swap | 新增 2 GB swap 文件 | 见 12.4 |
| 预估合计 | 约 780 MB | 留约 100 MB 余量 |

### 12.4 必须执行的前置动作

1. 创建 2 GB swap 文件并启用，写入 `/etc/fstab`
2. 若 yum 安装失败，先切换 vault 源（**改动前备份原有 repo 文件**）
3. 防火墙只保留 22 / 80 / 443，8080 不对外开放

### 12.5 部署前置检查（D0）

上线前逐条确认，任一不满足则不上线：

- [ ] `java -version` 输出 17
- [ ] `systemctl is-active mariadb`（或 mysql）为 active
- [ ] `redis-cli ping` 返回 PONG
- [ ] `swapon --show` 有 2 GB
- [ ] `ss -tlnp | grep 8080` 只显示 127.0.0.1
- [ ] `ADMIN_TOKEN` 已写入 systemd 环境变量，长度 ≥ 32
- [ ] nginx 配置已备份，且 `nginx -t` 通过

### 12.6 不做什么

- 不做蓝绿部署、不做滚动发布（单机无意义）
- 不做容器化改造
- 不做 CI/CD 自动部署（P0 阶段手动 scp + restart 即可，但要写进 README）
