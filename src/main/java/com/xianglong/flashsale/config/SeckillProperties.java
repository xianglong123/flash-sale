package com.xianglong.flashsale.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "seckill")
public class SeckillProperties {
    /** 管理端 token，生产必须由环境变量注入（requirement 7.4） */
    private String adminToken;
    /** 全局降级开关 */
    private boolean enabled = true;
}
