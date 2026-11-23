# P0 设计说明

> 本文在编码前产出，用于确认方案。结论性内容直接对应 `requirement.md` 第 5、9 节。
> **未确认前不写实现代码。**

## 1. 目录结构

```
src/main/java/com/xianglong/flashsale/
├── common/
│   ├── Result.java            # 统一响应体 {code, message, data, traceId}
│   ├── ErrorCode.java         # 枚举：SOLD_OUT / ALREADY_PARTICIPATED / ...
│   ├── TraceIdFilter.java     # 生成 traceId 放 MDC 和响应头
│   └── BizException.java
├── config/
│   ├── RedisConfig.java       # 序列化 + Lua 脚本注册（DefaultRedisScript 单例）
│   ├── WebConfig.java         # X-User-Id 解析为参数
│   └── MetricsConfig.java     # 计数器与 Timer
├── domain/
│   ├── Item.java
│   ├── Seckill.java
│   └── SeckillOrder.java
├── repository/
├── service/
│   ├── SeckillService.java    # 查询活动
│   ├── OrderService.java      # 下单主链路
│   └── RateLimitService.java  # 限流（Redis）
├── controller/
│   └── SeckillController.java
└── reconcile/
    └── ReconcileJob.java      # 扫描异常单并补齐（P0 只做记录与重试，不做回滚）
```

## 2. 下单主链路时序

```
请求 → TraceIdFilter → 参数校验(X-User-Id 合法)
     → 限流(Redis NX+EXPIRE 或 LUA 原子计数)      超限 → 429
     → 读活动元数据(Redis Hash)                    缺失/过期 → 403 NOT_IN_WINDOW 或 503 未就绪
     → 时间窗判断                                   不匹配 → 403
     → LUA 原子脚本（库存 + 用户集合）                → -1 已参与 409 / 0 售罄 409 / -2 未就绪 503
     → 同步 insert 订单(DB)                          → 唯一键冲突 409 / 其他异常 500
     → 返回 200 {orderNo}
```

关键点：**时间窗判断、限流、库存全部走 Redis**，DB 只在最后一步被调用一次。

## 3. Redis Key 设计

| Key | 类型 | 写入时机 | TTL |
| --- | --- | --- | --- |
| `seckill:stock:{id}` | String | 预热时写入总量 | 结束 + 24h |
| `seckill:user:{id}` | Set | 扣减成功时 SADD | 结束 + 24h |
| `seckill:meta:{id}` | Hash（startTime/endTime/totalStock/status） | 预热 | 结束 + 24h |
| `seckill:rate:{userId}:{id}` | String（计数器） | 每次请求 INCR | 1s |
| `seckill:rate:ip:{ip}` | String | 同上 | 1s |

**预热（boot 时执行）**：把 DB 中 `available_stock` 写入 stock key，`start_time/end_time` 写入 meta。预热失败时应用仍可启动，但下单返回 503 `SYSTEM_NOT_READY`。

## 4. 原子扣减脚本（逻辑说明）

```
KEYS[1]=stock, KEYS[2]=userSet, ARGV[1]=userId

if SISMEMBER(KEYS[2], ARGV[1]) == 1 then return -1      -- 已参与
stock = GET(KEYS[1])
if not stock then return -2                              -- 未预热
if tonumber(stock) <= 0 then return 0                    -- 售罄
DECR(KEYS[1])
SADD(KEYS[2], ARGV[1])
return 1                                                 -- 成功
```

三点必须遵守：

1. 判断 + 扣减 + 记录用户必须在**同一个脚本**里，否则并发下会多扣。
2. 脚本内不做任何 `if stock < 0 then INCR` 的"自我修复"，写操作失败让它失败。
3. `DefaultRedisScript` 必须注册为单例 bean，不要每次调用 new 一个（会反复编译脚本）。

## 5. 幂等三层

| 层 | 手段 | 作用 |
| --- | --- | --- |
| 1 | Redis Set `seckill:user:{id}` | 挡住 99% 的重复请求，不给 DB 压力 |
| 2 | DB `UNIQUE(user_id, seckill_id)` | 兜住 Redis 失效、误删、多实例竞争 |
| 3 | 捕获 `DuplicateKeyException` → 查已有订单返回 409 | 把数据库异常翻译成业务码，不允许冒到 500 |

第 3 层是**必须实现**的。少了它，并发下会出现 500 风暴。

## 6. 异常路径表

| 场景 | 处理 | 返回 | 是否影响库存 |
| --- | --- | --- | --- |
| Redis 超时 / 连接失败 | 直接拒绝 | 503 SYSTEM_BUSY | 否 |
| 活动未预热 | 拒绝 | 503 SYSTEM_NOT_READY | 否 |
| 限流超限 | 拒绝 | 429 | 否 |
| 已参与 | 拒绝 | 409 ALREADY_PARTICIPATED | 否 |
| 售罄 | 拒绝 | 409 SOLD_OUT | 否 |
| 预扣成功 + DB 插入成功 | 正常 | 200 | 已扣 |
| 预扣成功 + DB 唯一键冲突 | 视为已参与 | 409 | 已扣（不补） |
| **预扣成功 + DB 其他异常** | 写 RECONCILE 日志 + 落 reconcile 表 | 500 | **已扣，不回滚** |

最后一行是这份设计的核心取舍，对应 requirement.md 5.4：

- 不回滚 Redis 库存。回滚会与新的扣减交叉，产生超卖。
- 补偿任务每 5 分钟扫一次 reconcile 表，补插订单（幂等，靠唯一索引）。
- 已知的短暂状态：用户可能先收到 500，重试收到"已参与"但订单还没补齐。README 里要写明这个现象，避免误判为 bug。

## 7. 参数取值

| 参数 | 值 | 理由 |
| --- | --- | --- |
| Tomcat `max-threads` | 200 | 2000 QPS / 单请求 ~5ms 阻塞，够用 |
| Hikari `maximum-pool-size` | 30 | 每秒写入仅约 200 次，不需要大池 |
| Hikari `connection-timeout` | 3000ms | 与 requirement 一致 |
| Redis `timeout` | 500ms | 超过直接拒绝，不排队 |
| 下单接口线程内 DB 调用 | 同步 1 次 | 不做异步，保证返回即落库 |
| Actuator | 暴露 health + metrics | 验收要读指标 |

## 8. 与需求文档的对应

| requirement.md | 本设计章节 |
| --- | --- |
| 5.2 竞态点 | 第 4、5 节 |
| 5.4 扣减取舍 | 第 6 节末 |
| 5.6 限流 | 第 3 节 |
| 6.1 禁止静默失败 | 第 6 节整表 |
| 8.2 指标 | 第 7 节 |

## 9. 待确认

- [ ] ORM：JPA（本文默认）还是 MyBatis？换 MyBatis 需要改 requirement 依赖白名单，以及 `repository/` 的实现方式。
- [ ] 是否同意「预扣成功后 DB 失败不回滚，走补偿」这个取舍。
- [ ] 是否同意 P0 就同步落库（不引入 MQ 削峰）。
