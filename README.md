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
