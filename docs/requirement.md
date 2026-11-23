# flash-sale · 秒杀系统 需求文档

| 项 | 值 |
| --- | --- |
| 项目名 | flash-sale |
| 语言/框架 | Java 17 + Spring Boot 3.2.x |
| 存储 | MySQL 8.0 + Redis 7.x |
| 压测 | k6（脚本化，可纳入仓库） |
| 部署目标 | 第一期本地单机，不上云 |
| 文档版本 | v1 |
| 本文档的作用 | 既是开发需求，也是 AI 编码的约束合同和验收标准 |

---

## 0. 交付计划（分批，禁止一次全做）

| 阶段 | 范围 | 验收门槛 |
| --- | --- | --- |
| P0 | 活动查询 + 下单主链路（Redis 预扣 + DB 落库）+ 幂等 + 限流 | 1000 库存 / 10000 并发不超卖不少卖 |
| P1 | 订单状态机 + 超时未支付回补 + 管理端活动配置 | 回补不产生负库存、不产生重复订单 |
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

- 禁止自行扩展功能：不做购物车、不做优惠券、不做支付对接（支付用 mock 接口）、不做用户注册登录体系（用 header 传 userId）。
- 禁止改动数据库表结构以外的任何配置文件默认值（连接池大小等改动必须在 PR 说明里写原因）。
- 禁止在 `entity` 层加入业务逻辑。

### 1.4 明确不做

- 不做分布式事务
- 不做多机房
- 不做风控模型（只做简单的频率限制）
- 不做前端页面（只提供 REST 接口 + Swagger）

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

---

## 5. 并发与一致性（本文档的核心）

> 每一项都必须实现，不适用也要在验收报告里说明理由。

### 5.1 并发画像

| 项 | 值 |
| --- | --- |
| 目标峰值 QPS | 2000（下单接口） |
| 并发用户 | 10000，5 秒内到达 |
| 库存 | 1000 |
| 预期成功率 | 恰好 1000 单成功，其余全部明确拒绝（不是超时、不是 500） |
| 接口 P99 | < 200ms（2000 QPS 下） |

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

- 认证：无真实认证，`X-User-Id` 由调用方传入；**必须在代码注释中标注这是演示用，不具备真实安全性**
- 越权：下单时必须校验 `seckillId` 存在且时间窗有效，不校验 userId 归属（因为本身就是当前用户）
- 输入校验：`seckillId` 必须为正整数；`X-User-Id` 长度 ≤ 20 位数字；非法输入返回 400，不抛异常
- 敏感数据：不记录完整请求头；日志中 userId 只记录脱敏值（保留后 4 位）
- 禁止在 URL 里传输任何密钥

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

### 9.4 交付物清单

- `docs/requirement.md`（本文件）
- `README.md`：启动方式、压测方式、排查路径
- `loadtest/k6-seckill.js`：C1~C4 的可执行脚本
- `docs/report-P0.md`：含 k6 报告截图 + SQL 核对结果
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

- [ ] 技术栈版本：JDK 17 / Spring Boot 3.2.x / MySQL 8 / Redis 7
- [ ] ORM 用 JPA 还是 MyBatis（本需求默认 JPA，如需 MyBatis 需改禁止项里的依赖白名单）
- [ ] 压测工具用 k6（本地需安装）
- [ ] 是否第一期就用 Docker Compose 起 MySQL + Redis
- [ ] P0 的目标 QPS 是否就按 2000（本地单机的现实上限）
