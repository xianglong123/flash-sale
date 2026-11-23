package com.xianglong.flashsale.config;

import com.xianglong.flashsale.service.WarmUpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class WarmUpRunner implements ApplicationRunner {

    private final WarmUpService warmUpService;

    /**
     * 启动即预热。P0-002 修复点：Redis 重启丢数据后，不允许系统一直停在「活动未就绪」。
     * 这里失败也不阻断启动 —— 运行期还有 WarmUpJob 每 60 秒兜底。
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            int n = warmUpService.rehydrateAll();
            log.info("[WARMUP] 启动预热完成，重建 {} 个活动", n);
        } catch (Exception e) {
            log.error("[WARMUP] 启动预热失败（Redis 可能还没就绪），交由定时任务重试: {}", e.toString());
        }
    }
}
