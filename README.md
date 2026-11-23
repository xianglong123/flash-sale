# flash-sale

秒杀系统（Spring Boot 3.2 + MySQL 8 + Redis 7）。

这个仓库的目的不只是做一个秒杀系统，而是验证一条工作流：**需求文档 → AI 实现 → 人验收**。

## 文档

- `docs/requirement.md` —— 需求与验收标准（人写，是 AI 的约束合同）
- `docs/需求文档规范.md` —— 写这类文档的通用规范
- `docs/design-P0.md` —— P0 阶段设计说明（编码前必须先确认）
- `docs/report-P0.md` —— P0 验收报告（待产出）

## 阶段

| 阶段 | 范围 | 状态 |
| --- | --- | --- |
| P0 | 活动查询 + 下单主链路 + 幂等 + 限流 | 进行中 |
| P1 | 订单状态机 + 超时回补 + 管理端配置 | 未开始 |
| P2 | 压测报告 + 热点优化 + 故障演练 | 未开始 |

P0 未通过验收，不开始 P1。

## 快速开始

```bash
docker compose up -d          # MySQL 8 + Redis 7
mvn spring-boot:run           # 启动应用
curl localhost:8080/actuator/health
```

## 核心验收（P0）

10000 用户 / 5 秒内并发下单 / 1000 库存 → **成功数必须恰好 1000**，不超卖、不少卖、不重复。

详见 `docs/requirement.md` 第 9 节。

## 本地运行（v3 实际形态）

依赖：JDK 17、MySQL（本地 3306）、Redis（本地 6379）。

```bash
# 1. 确认 MySQL / Redis 在线
MYSQL_PWD=12345678 mysql -h127.0.0.1 -uroot -e "SELECT VERSION();"
redis-cli ping

# 2. 启动（必须显式指定 JDK 17）
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn spring-boot:run
```

> 注意：本机 `mvn` 默认使用 Homebrew JDK 23，lombok 注解处理在 JDK 23 下会静默失效（报大量“找不到符号 getXxx”）。必须用 JDK 17 编译运行。

页面：
- 展示页 `http://127.0.0.1:8080/?id=<seckillId>`
- 管理端 `http://127.0.0.1:8080/admin.html`

管理员令牌默认 `dev-only-token-please-change-0123456789abcdef`，由 `seckill.admin-token` 配置，请求头 `X-Admin-Token`。

## 已验证结果（本地实测）

| 场景 | 结果 |
|---|---|
| 2000 并发 / 库存 200 | 成功 200，409 共 1800，Redis 剩余 0，DB 订单 200 |
| 1000 并发 / 库存 100 | 成功 100，409 共 900，DB 订单 100 |
| 同一用户重复下单 | 第二次起返回 `ALREADY_PARTICIPATED`（409） |
| 连点两家（同一秒） | 先被 `RATE_LIMITED` 拦截（429），限流优先于幂等 |
| 进行中减库存 | `STOCK_DECREASE_FORBIDDEN`（400） |
| 进行中加库存 | 允许，Redis 与 DB 同步 |
| 活动结束后下单 | `NOT_IN_WINDOW`（403） |
| 缺少 / 错误管理令牌 | 401 |
| 非法路径参数 `/api/seckill/abc` | 400 |

结论：零超卖、零少卖。已知限制：DB 的 `available_stock` 只是初始快照，真实库存在 Redis；Redis 被清空时下单会 fail-safe 返回 `SYSTEM_NOT_READY`，不会超卖，但需要管理端重新预热。
