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
│   ├── SeckillController.java   # 查询 / 下单 / stats
│   └── AdminController.java     # 创建 / 调库存 / 开始 / 结束 / 列表
├── admin/
│   ├── AdminAuthFilter.java     # X-Admin-Token 定长比较 + 限流
│   └── AdminAudit.java          # 管理操作审计日志（token 只记 sha256 前 8 位）
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

| 参数 | 本地压测 | 服务器演示 | 理由 |
| --- | --- | --- | --- |
| Tomcat `max-threads` | 200 | 60 | 服务器 2 核，再多线程只是排队 |
| Hikari `maximum-pool-size` | 30 | 12 | 演示环境并发低，池大会吃内存 |
| Hikari `connection-timeout` | 3000ms | 3000ms | 与 requirement 一致 |
| Redis `timeout` | 500ms | 500ms | 超过直接拒绝，不排队 |
| JVM 堆 | 默认 | `-Xmx256m` | 服务器总内存 1.7G，见 requirement 12.3 |
| 下单接口线程内 DB 调用 | 同步 1 次 | 同步 1 次 | 不做异步，保证返回即落库 |
| Actuator | health + metrics | health + metrics | 验收要读指标 |

两套参数用 Spring profile 区分：`application-local.yml`（压测）与 `application-prod.yml`（服务器）。`application-local.yml` 已在 .gitignore 中。

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

---

## 10. 管理端设计（v2）

### 10.1 鉴权链路

```
请求 → AdminAuthFilter（只拦 /api/admin/**）
     → 取 header X-Admin-Token
     → 缺失 → 401
     → MessageDigest.isEqual(输入, 配置值) 定长比较 → 不等 401
     → 限流：Redis INCR admin:rate:{minute} > 30 → 429
     → 放行 → 业务处理
```

启动时 `ADMIN_TOKEN` 为空直接抛异常退出（fail-fast），不允许以无鉴权状态运行。

### 10.2 库存调整的两种情形

| 情形 | 允许 | 执行顺序 |
| --- | --- | --- |
| `NOT_STARTED` 下调 `total_stock` | 增/减均可 | 先 `SET` Redis 库存为新值，再 `UPDATE` DB |
| `ONGOING` 下加库存 | 只增 | 先 `INCRBY` Redis，再 `UPDATE ... available_stock = available_stock + n` |
| 任意状态下减少 | **拒绝** | 返回 400，不动任何数据 |

为什么先 Redis 后 DB：Redis 是扣减的唯一判据。先改 DB 再改 Redis，中间窗口内会出现"DB 显示的库存比实际可卖的多"，用户看到能买却买不到。反过来，Redis 已加、DB 未加，最多是暂时少显示，不影响售卖正确性。

按 5.4 的取舍，DB 更新失败时不回滚 Redis，只记 RECONCILE。

### 10.3 审计日志

每次管理操作输出一行：

```
[ADMIN] action=stock_up seckillId=1 delta=500 by=3f2a1b9c result=ok costMs=12
```

`by` 是 token 的 SHA-256 前 8 位，**永不记录原文**。

## 11. 前端设计（v2）

### 11.1 两个页面，零构建

| 页面 | 路径 | 权限 |
| --- | --- | --- |
| 展示页 | `/index.html` | 匿名 |
| 管理页 | `/admin.html` | 需输入 token |

纯 HTML + 原生 JS，放在 `src/main/resources/static/`，由 Spring Boot 直接提供，不引入任何前端依赖。

### 11.2 展示页

- 每 1500ms `fetch('/api/seckill/{id}/stats')`
- 进度条 = `soldCount / totalStock`
- 倒计时用 `serverTime` 与本地时间差校正，避免客户端时钟不准
- 请求失败时显示上次数据 + 「数据更新中」提示，**不要把界面清空**

### 11.3 管理页

- token 输入后存 `sessionStorage`，关掉标签页即失效
- 所有请求带 `X-Admin-Token`
- 收到 401 立即清空 token 并提示重新输入
- 库存输入框做前端校验（1~100000 整数），但**后端必须再校验一遍**

## 12. 部署设计（v2）

### 12.1 服务器免 Docker 装机顺序

```
1. 加 2G swap（fstab 持久化）
2. 装 JDK：Temurin 17 tarball → /opt/jdk17，配 /etc/profile.d/jdk.sh
3. 装 Redis：yum（失败则切 vault 源）→ 改 redis.conf（maxmemory 48mb、bind 127.0.0.1）
4. 装 MariaDB/MySQL → my.cnf 调 innodb_buffer_pool_size=96M、max_connections=50
5. 建库建表（用 schema.sql 初始化）
6. 本地 mvn package → scp target/flash-sale.jar → /opt/flash-sale/app.jar
7. systemd unit：EnvironmentFile 放 ADMIN_TOKEN，ExecStart 带 -Xmx256m，Restart=always
8. nginx：新建 server 块，443 反代 127.0.0.1:8080，80 跳 443
9. 防火墙：只留 22/80/443
```

### 12.2 nginx 反代要点

- `proxy_pass http://127.0.0.1:8080;`
- 传 `X-Real-IP` 与 `X-Forwarded-For`（限流要区分用户）
- 下单接口 `proxy_read_timeout` 不低于 5s
- 静态文件可直接由 nginx 提供，减轻应用压力（可选）

### 12.3 发布与回滚

```
发布：scp jar → systemctl restart flash-sale → curl /actuator/health
回滚：保留上一版 jar 为 app.jar.prev，一条 mv 命令 + restart
```

**要求：每次发布前把当前 jar 备份成 `app.jar.prev`，回滚必须在 1 分钟内完成。**

## 13. 待确认（v2）

- [ ] ORM：JPA（默认）还是 MyBatis
- [ ] 数据库：MariaDB 10.x 还是 MySQL 5.7（取决于服务器 yum 源实际情况）
- [ ] 是否同意「先 Redis 后 DB」的调整顺序
- [ ] 演示域名（443 走现有 nginx）
- [ ] 是否同意前端用纯 HTML，不做任何构建
