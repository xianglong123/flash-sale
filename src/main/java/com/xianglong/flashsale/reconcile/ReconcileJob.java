package com.xianglong.flashsale.reconcile;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xianglong.flashsale.domain.Item;
import com.xianglong.flashsale.domain.Reconcile;
import com.xianglong.flashsale.domain.Seckill;
import com.xianglong.flashsale.domain.SeckillOrder;
import com.xianglong.flashsale.mapper.ReconcileMapper;
import com.xianglong.flashsale.mapper.SeckillOrderMapper;
import com.xianglong.flashsale.service.SeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReconcileJob {

    private static final int MAX_RETRY = 5;

    private final ReconcileMapper reconcileMapper;
    private final SeckillOrderMapper orderMapper;
    private final SeckillService seckillService;

    /** 每 5 分钟扫一次未解决的异常单，补插订单（幂等靠唯一索引） */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void run() {
        List<Reconcile> pending = reconcileMapper.selectList(new QueryWrapper<Reconcile>()
                .eq("resolved", 0)
                .lt("retry_count", MAX_RETRY));
        if (pending.isEmpty()) {
            return;
        }
        log.info("[RECONCILE] start, pending={}", pending.size());
        for (Reconcile rec : pending) {
            try {
                Seckill s = seckillService.mustGet(rec.getSeckillId());
                Item item = seckillService.mustGetItem(s.getItemId());

                SeckillOrder o = new SeckillOrder();
                o.setOrderNo("FS" + System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase());
                o.setUserId(rec.getUserId());
                o.setSeckillId(rec.getSeckillId());
                o.setItemId(item.getId());
                o.setAmount(item.getPrice());
                o.setStatus("PENDING_PAY");
                o.setCreatedAt(LocalDateTime.now());
                orderMapper.insert(o);

                rec.setResolved(1);
                reconcileMapper.updateById(rec);
                log.info("[RECONCILE] fixed userId={} seckillId={}", rec.getUserId(), rec.getSeckillId());
            } catch (DuplicateKeyException e) {
                // 订单已存在，说明是重复补偿
                rec.setResolved(1);
                reconcileMapper.updateById(rec);
            } catch (Exception e) {
                rec.setRetryCount(rec.getRetryCount() + 1);
                reconcileMapper.updateById(rec);
                log.warn("[RECONCILE] retry failed userId={} seckillId={} retry={} err={}",
                        rec.getUserId(), rec.getSeckillId(), rec.getRetryCount(), e.toString());
            }
        }
    }
}
