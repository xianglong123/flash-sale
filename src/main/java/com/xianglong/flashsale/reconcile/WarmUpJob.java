package com.xianglong.flashsale.reconcile;

import com.xianglong.flashsale.service.WarmUpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 运行期缓存自愈。Redis 单独重启（应用不掉）时，靠它把活动状态捞回来。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WarmUpJob {

    private final WarmUpService warmUpService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void run() {
        try {
            int n = warmUpService.rehydrateMissing();
            if (n > 0) {
                log.warn("[WARMUP] 运行期检测到 {} 个活动缓存丢失，已自动重建", n);
            }
        } catch (Exception e) {
            log.warn("[WARMUP] 自愈任务跳过本轮: {}", e.toString());
        }
    }
}
