package com.xianglong.flashsale.service;

import com.xianglong.flashsale.common.BizException;
import com.xianglong.flashsale.common.ErrorCode;
import com.xianglong.flashsale.common.TraceIdFilter;
import com.xianglong.flashsale.config.SeckillProperties;
import com.xianglong.flashsale.domain.Reconcile;
import com.xianglong.flashsale.domain.SeckillOrder;
import com.xianglong.flashsale.mapper.ReconcileMapper;
import com.xianglong.flashsale.mapper.SeckillOrderMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> seckillScript;
    private final SeckillOrderMapper orderMapper;
    private final ReconcileMapper reconcileMapper;
    private final SeckillService seckillService;
    private final RateLimitService rateLimitService;
    private final SeckillProperties props;
    private final MeterRegistry meterRegistry;

    public SeckillOrder create(Long userId, Long seckillId, String ip) {
        meterRegistry.counter("seckill.request.total").increment();

        // 0. 全局降级开关
        if (!props.isEnabled()) {
            reject("disabled");
            throw new BizException(ErrorCode.SYSTEM_BUSY);
        }

        // 1. 限流（用户级 1 次/秒，IP 级 20 次/秒）
        if (!rateLimitService.allow("seckill:rate:u:" + userId + ":" + seckillId, 1, 1)) {
            reject("rate_limited");
            throw new BizException(ErrorCode.RATE_LIMITED);
        }
        if (ip != null && !rateLimitService.allow("seckill:rate:ip:" + ip, 20, 1)) {
            reject("rate_limited");
            throw new BizException(ErrorCode.RATE_LIMITED);
        }

        String traceId = MDC.get(TraceIdFilter.TRACE);
        log.info("[REQ] traceId={} userId={} seckillId={}", traceId, mask(userId), seckillId);

        // 2. 活动元数据（全部来自 Redis，不查 DB）
        Map<Object, Object> meta = redis.opsForHash().entries(RedisKeys.meta(seckillId));
        if (meta.isEmpty()) {
            log.warn("[CHECK] meta missing seckillId={} -> NOT_READY", seckillId);
            throw new BizException(ErrorCode.SYSTEM_NOT_READY);
        }

        // 管理端「结束」会立刻改写 status，必须优先于时间窗判断
        String status = String.valueOf(meta.getOrDefault("status", ""));
        if ("ENDED".equals(status)) {
            reject("window");
            throw new BizException(ErrorCode.NOT_IN_WINDOW, "活动已结束", Map.of("status", "ENDED"));
        }

        long start = Long.parseLong(String.valueOf(meta.get("startTime")));
        long end = Long.parseLong(String.valueOf(meta.get("endTime")));
        long now = Instant.now().getEpochSecond();
        if (now < start) {
            reject("window");
            throw new BizException(ErrorCode.NOT_IN_WINDOW, "活动未开始", Map.of("status", "NOT_STARTED"));
        }
        if (now > end) {
            reject("window");
            throw new BizException(ErrorCode.NOT_IN_WINDOW, "活动已结束", Map.of("status", "ENDED"));
        }

        // 3. Lua 原子扣减
        long t0 = System.currentTimeMillis();
        Long result = redis.execute(
                seckillScript,
                List.of(RedisKeys.stock(seckillId), RedisKeys.users(seckillId)),
                String.valueOf(userId));
        meterRegistry.timer("seckill.deduct.latency").record(System.currentTimeMillis() - t0, java.util.concurrent.TimeUnit.MILLISECONDS);

        long r = result == null ? -99 : result;
        if (r == -1) {
            reject("duplicated");
            throw new BizException(ErrorCode.ALREADY_PARTICIPATED);
        }
        if (r == 0) {
            reject("sold_out");
            throw new BizException(ErrorCode.SOLD_OUT);
        }
        if (r == -2) {
            throw new BizException(ErrorCode.SYSTEM_NOT_READY);
        }
        if (r != 1) {
            throw new BizException(ErrorCode.SYSTEM_BUSY);
        }

        log.info("[DEDUCT] result=success remain={} userId={} seckillId={}", redis.opsForValue().get(RedisKeys.stock(seckillId)), mask(userId), seckillId);

        // 4. 落库
        SeckillOrder order = new SeckillOrder();
        order.setOrderNo(generateOrderNo());
        order.setUserId(userId);
        order.setSeckillId(seckillId);
        order.setItemId(Long.parseLong(String.valueOf(meta.get("itemId"))));
        order.setAmount(new BigDecimal(String.valueOf(meta.get("price"))));
        order.setStatus("PENDING_PAY");
        order.setCreatedAt(LocalDateTime.now());

        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            // 幂等第三层：唯一索引兜底，翻译成业务码，不冒 500
            reject("duplicated");
            throw new BizException(ErrorCode.ALREADY_PARTICIPATED);
        } catch (Exception e) {
            // 预扣成功但落库失败：不回滚库存，记异常单（requirement 5.4）
            log.error("[FAIL] db insert failed, mark reconcile traceId={} userId={} seckillId={}",
                    traceId, mask(userId), seckillId, e);
            saveReconcile(userId, seckillId, e, traceId);
            meterRegistry.counter("seckill.reconcile.count").increment();
            throw new BizException(ErrorCode.INTERNAL_ERROR);
        }

        // 5. 展示用最近订单（脱敏）
        String row = mask(userId) + "|" + order.getCreatedAt().format(FMT);
        redis.opsForList().leftPush(RedisKeys.recent(seckillId), row);
        redis.opsForList().trim(RedisKeys.recent(seckillId), 0, 9);

        meterRegistry.counter("seckill.order.success").increment();
        log.info("[ORDER] orderNo={} status=PENDING_PAY userId={} seckillId={}", order.getOrderNo(), mask(userId), seckillId);
        return order;
    }

    private void reject(String reason) {
        meterRegistry.counter("seckill.order.reject", "reason", reason).increment();
    }

    private void saveReconcile(Long userId, Long seckillId, Exception e, String traceId) {
        try {
            Reconcile rec = new Reconcile();
            rec.setUserId(userId);
            rec.setSeckillId(seckillId);
            rec.setReason(e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
            rec.setTraceId(traceId);
            rec.setRetryCount(0);
            rec.setResolved(0);
            rec.setCreatedAt(LocalDateTime.now());
            reconcileMapper.insert(rec);
        } catch (Exception ignore) {
            log.warn("[RECONCILE] insert reconcile record failed userId={} seckillId={}", mask(userId), seckillId);
        }
    }

    private String generateOrderNo() {
        return "FS" + System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    /** 用户 ID 只保留后 4 位 */
    public static String mask(Long userId) {
        if (userId == null) {
            return "****";
        }
        String s = String.valueOf(userId);
        if (s.length() <= 4) {
            return "****" + s;
        }
        return "****" + s.substring(s.length() - 4);
    }
}
