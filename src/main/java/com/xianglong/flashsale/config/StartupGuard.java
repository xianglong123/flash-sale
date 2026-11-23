package com.xianglong.flashsale.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StartupGuard implements ApplicationRunner {

    private final SeckillProperties props;

    /** requirement 7.4：ADMIN_TOKEN 未配置时不允许启动（fail-fast） */
    @Override
    public void run(ApplicationArguments args) {
        String token = props.getAdminToken();
        if (token == null || token.isBlank() || token.length() < 32) {
            throw new IllegalStateException("ADMIN_TOKEN 缺失或长度不足 32，拒绝启动（requirement 7.4）");
        }
    }
}
