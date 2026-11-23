package com.xianglong.flashsale.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xianglong.flashsale.common.BizException;
import com.xianglong.flashsale.common.ErrorCode;
import com.xianglong.flashsale.domain.Item;
import com.xianglong.flashsale.domain.Seckill;
import com.xianglong.flashsale.mapper.ItemMapper;
import com.xianglong.flashsale.mapper.SeckillMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillService {

    private final SeckillMapper seckillMapper;
    private final ItemMapper itemMapper;
    private final StringRedisTemplate redis;

    // ---------- 管理端 ----------

    @Transactional
    public Seckill create(String itemName, BigDecimal price, Integer totalStock, Integer durationSeconds) {
        if (totalStock == null || totalStock < 1 || totalStock > 100000) {
            throw new BizException(ErrorCode.BAD_REQUEST, "totalStock 取值 1~100000");
        }
        int duration = (durationSeconds == null || durationSeconds < 10) ? 60 : durationSeconds;

        Item item = new Item();
        item.setName(itemName);
        item.setPrice(price);
        item.setCreatedAt(LocalDateTime.now());
        itemMapper.insert(item);

        Seckill s = new Seckill();
        s.setItemId(item.getId());
        s.setTotalStock(totalStock);
        s.setAvailableStock(totalStock);
        LocalDateTime now = LocalDateTime.now();
        s.setStartTime(now);
        s.setEndTime(now.plusSeconds(duration));
        s.setStatus("NOT_STARTED");
        s.setVersion(0);
        seckillMapper.insert(s);

        // 未开始也预热，保证 meta 可查；但时间窗未到，下单会被 NOT_IN_WINDOW 拦下
        warmUp(s, item);
        log.info("[ADMIN] create seckillId={} stock={} duration={}s", s.getId(), totalStock, duration);
        return s;
    }

    @Transactional
    public Seckill start(Long id) {
        Seckill s = mustGet(id);
        if (!"NOT_STARTED".equals(s.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只有未开始的活动才能启动");
        }
        long duration = Duration.between(s.getStartTime(), s.getEndTime()).getSeconds();
        if (duration < 10) {
            duration = 60;
        }
        LocalDateTime now = LocalDateTime.now();
        s.setStartTime(now);
        s.setEndTime(now.plusSeconds(duration));
        s.setStatus("ONGOING");
        s.setAvailableStock(s.getTotalStock());
        seckillMapper.updateById(s);

        warmUp(s, mustGetItem(s.getItemId()));
        log.info("[ADMIN] start seckillId={} window={}~{}", id, s.getStartTime(), s.getEndTime());
        return s;
    }

    @Transactional
    public Seckill end(Long id) {
        Seckill s = mustGet(id);
        if (!"ONGOING".equals(s.getStatus()) && !"SOLD_OUT".equals(s.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "活动不在进行中");
        }
        s.setStatus("ENDED");
        seckillMapper.updateById(s);
        // 双保险：状态置 ENDED 的同时把 Redis 时间窗收到当前，任何路径都进不来
        Map<String, String> patch = new HashMap<>();
        patch.put("status", "ENDED");
        patch.put("endTime", String.valueOf(Instant.now().getEpochSecond()));
        redis.opsForHash().putAll(RedisKeys.meta(id), patch);
        log.info("[ADMIN] end seckillId={}", id);
        return s;
    }

    /**
     * 库存调整。进行中只允许增加，且先 Redis 后 DB（design-P0 10.2）。
     */
    @Transactional
    public Seckill updateStock(Long id, Integer totalStock) {
        if (totalStock == null || totalStock < 1 || totalStock > 100000) {
            throw new BizException(ErrorCode.BAD_REQUEST, "totalStock 取值 1~100000");
        }
        Seckill s = mustGet(id);
        int current = s.getTotalStock();

        if ("ONGOING".equals(s.getStatus())) {
            if (totalStock < current) {
                throw new BizException(ErrorCode.STOCK_DECREASE_FORBIDDEN);
            }
            int delta = totalStock - current;
            if (delta == 0) {
                return s;
            }
            // 先 Redis：Redis 是扣减的唯一判据
            redis.opsForValue().increment(RedisKeys.stock(id), delta);
            s.setTotalStock(totalStock);
            s.setAvailableStock(s.getAvailableStock() + delta);
            seckillMapper.updateById(s);
            redis.opsForHash().put(RedisKeys.meta(id), "totalStock", String.valueOf(totalStock));
            log.info("[ADMIN] stock_up seckillId={} delta={}", id, delta);
        } else if ("NOT_STARTED".equals(s.getStatus())) {
            s.setTotalStock(totalStock);
            s.setAvailableStock(totalStock);
            seckillMapper.updateById(s);
            redis.opsForValue().set(RedisKeys.stock(id), String.valueOf(totalStock));
            redis.opsForHash().put(RedisKeys.meta(id), "totalStock", String.valueOf(totalStock));
            log.info("[ADMIN] stock_reset seckillId={} total={}", id, totalStock);
        } else {
            throw new BizException(ErrorCode.BAD_REQUEST, "活动已结束，不能调整库存");
        }
        return s;
    }

    public List<Seckill> list() {
        return seckillMapper.selectList(new QueryWrapper<Seckill>().orderByDesc("id"));
    }

    // ---------- 查询 ----------

    public Seckill mustGet(Long id) {
        Seckill s = seckillMapper.selectById(id);
        if (s == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return s;
    }

    public Item mustGetItem(Long itemId) {
        Item item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "商品不存在");
        }
        return item;
    }

    public Map<String, Object> detail(Long id) {
        Seckill s = mustGet(id);
        Item item = mustGetItem(s.getItemId());
        Map<String, Object> m = new HashMap<>();
        m.put("seckillId", s.getId());
        m.put("itemName", item.getName());
        m.put("price", item.getPrice());
        m.put("totalStock", s.getTotalStock());
        m.put("availableStock", currentStock(s));
        m.put("status", s.getStatus());
        m.put("startTime", s.getStartTime());
        m.put("endTime", s.getEndTime());
        return m;
    }

    public Map<String, Object> stats(Long id) {
        Seckill s = mustGet(id);
        Item item = mustGetItem(s.getItemId());
        int stock = currentStock(s);
        int total = s.getTotalStock();
        Map<String, Object> m = new HashMap<>();
        m.put("seckillId", id);
        m.put("itemName", item.getName());
        m.put("price", item.getPrice());
        m.put("status", s.getStatus());
        m.put("totalStock", total);
        m.put("availableStock", stock);
        m.put("soldCount", Math.max(0, total - stock));
        m.put("startTime", s.getStartTime());
        m.put("endTime", s.getEndTime());
        m.put("serverTime", LocalDateTime.now());
        m.put("recentOrders", recentOrders(id));
        return m;
    }

    public int currentStock(Seckill s) {
        String v = redis.opsForValue().get(RedisKeys.stock(s.getId()));
        if (v == null) {
            return s.getAvailableStock();
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return s.getAvailableStock();
        }
    }

    public List<Map<String, Object>> recentOrders(Long id) {
        List<String> raw = redis.opsForList().range(RedisKeys.recent(id), 0, 9);
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String r : raw) {
            String[] parts = r.split("\\|");
            Map<String, Object> one = new HashMap<>();
            one.put("userMasked", parts.length > 0 ? parts[0] : "****");
            one.put("createdAt", parts.length > 1 ? parts[1] : "");
            out.add(one);
        }
        return out;
    }

    // ---------- 预热 ----------

    public void warmUp(Seckill s, Item item) {
        Long id = s.getId();
        redis.opsForValue().set(RedisKeys.stock(id), String.valueOf(s.getAvailableStock()));
        Map<String, String> meta = new HashMap<>();
        meta.put("startTime", String.valueOf(epoch(s.getStartTime())));
        meta.put("endTime", String.valueOf(epoch(s.getEndTime())));
        meta.put("totalStock", String.valueOf(s.getTotalStock()));
        meta.put("status", s.getStatus());
        meta.put("itemId", String.valueOf(s.getItemId()));
        meta.put("price", String.valueOf(item.getPrice()));
        redis.opsForHash().putAll(RedisKeys.meta(id), meta);
        long ttl = Math.max(3600, epoch(s.getEndTime()) - epoch(LocalDateTime.now()) + 86400);
        redis.expire(RedisKeys.stock(id), Duration.ofSeconds(ttl));
        redis.expire(RedisKeys.meta(id), Duration.ofSeconds(ttl));
        redis.expire(RedisKeys.users(id), Duration.ofSeconds(ttl));
        redis.expire(RedisKeys.recent(id), Duration.ofSeconds(ttl));
    }

    public static long epoch(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toEpochSecond();
    }
}
