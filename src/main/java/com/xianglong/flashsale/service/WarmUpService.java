package com.xianglong.flashsale.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xianglong.flashsale.domain.Item;
import com.xianglong.flashsale.domain.Seckill;
import com.xianglong.flashsale.mapper.ItemMapper;
import com.xianglong.flashsale.mapper.ReconcileMapper;
import com.xianglong.flashsale.mapper.SeckillMapper;
import com.xianglong.flashsale.mapper.SeckillOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 缓存自愈：Redis 丢数据后，从数据库（订单表是唯一事实源）重建库存 / 元数据 / 幂等集合。
 *
 * 关键约束：
 * 1. 库存用 SETNX 写，绝不覆盖正在被扣减的实时值；
 * 2. 库存 = 总库存 - 已成交订单数 - 未解决异常单数（异常单是已预扣未落库的，必须扣掉）；
 * 3. 幂等集合从订单表回填，避免缓存丢失后同一用户重复下单。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WarmUpService {

    private static final int USER_BATCH = 1000;

    private final SeckillMapper seckillMapper;
    private final ItemMapper itemMapper;
    private final SeckillOrderMapper orderMapper;
    private final ReconcileMapper reconcileMapper;
    private final StringRedisTemplate redis;

    private List<Seckill> activeSeckills() {
        return seckillMapper.selectList(new QueryWrapper<Seckill>()
                .in("status", "NOT_STARTED", "ONGOING"));
    }

    /** 应用启动时：无条件重建所有未结束活动（缓存可能是空的，也可能残留旧值） */
    public int rehydrateAll() {
        List<Seckill> list = activeSeckills();
        int ok = 0;
        for (Seckill s : list) {
            if (rehydrate(s.getId())) {
                ok++;
            }
        }
        return ok;
    }

    /** 运行期自愈：只处理 meta 已经丢失的活动，正常活动一次 DB 都不碰 */
    public int rehydrateMissing() {
        List<Seckill> list = activeSeckills();
        int ok = 0;
        for (Seckill s : list) {
            boolean metaLost = !Boolean.TRUE.equals(redis.hasKey(RedisKeys.meta(s.getId())));
            if (metaLost && rehydrate(s.getId())) {
                ok++;
            }
        }
        return ok;
    }

    public boolean rehydrate(Long seckillId) {
        Seckill s = seckillMapper.selectById(seckillId);
        if (s == null || "ENDED".equals(s.getStatus())) {
            return false;
        }
        Item item = itemMapper.selectById(s.getItemId());
        if (item == null) {
            log.warn("[WARMUP] skip seckillId={} item missing", seckillId);
            return false;
        }

        int sold = orderMapper.countBySeckill(seckillId);
        int pending = reconcileMapper.countUnresolved(seckillId);
        int available = Math.max(0, s.getTotalStock() - sold - pending);

        // 1) 库存：SETNX。已经有值就说明 Redis 仍然活着，绝不能用 DB 快照覆盖实时扣减值
        String stockKey = RedisKeys.stock(seckillId);
        Boolean created = redis.opsForValue().setIfAbsent(stockKey, String.valueOf(available));

        // 2) 元数据：静态数据，幂等覆盖
        Map<String, String> meta = new HashMap<>();
        meta.put("startTime", String.valueOf(SeckillService.epoch(s.getStartTime())));
        meta.put("endTime", String.valueOf(SeckillService.epoch(s.getEndTime())));
        meta.put("totalStock", String.valueOf(s.getTotalStock()));
        meta.put("status", s.getStatus());
        meta.put("itemId", String.valueOf(s.getItemId()));
        meta.put("price", String.valueOf(item.getPrice()));
        redis.opsForHash().putAll(RedisKeys.meta(seckillId), meta);

        // 3) 幂等集合：从订单表回填，SADD 天然幂等
        List<Long> userIds = orderMapper.selectUserIds(seckillId);
        if (!userIds.isEmpty()) {
            for (int i = 0; i < userIds.size(); i += USER_BATCH) {
                List<String> batch = userIds
                        .subList(i, Math.min(i + USER_BATCH, userIds.size()))
                        .stream().map(String::valueOf).toList();
                redis.opsForSet().add(RedisKeys.users(seckillId), batch.toArray(new String[0]));
            }
        }

        long now = Instant.now().getEpochSecond();
        long ttlSeconds = Math.max(3600, SeckillService.epoch(s.getEndTime()) - now + 86400);
        Duration ttl = Duration.ofSeconds(ttlSeconds);
        redis.expire(stockKey, ttl);
        redis.expire(RedisKeys.meta(seckillId), ttl);
        redis.expire(RedisKeys.users(seckillId), ttl);

        log.info("[WARMUP] seckillId={} sold={} pending={} rebuildAvailable={} stockCreated={} idempotentUsers={} ttl={}s",
                seckillId, sold, pending, available, !Boolean.FALSE.equals(created), userIds.size(), ttlSeconds);
        return true;
    }
}
