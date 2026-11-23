package com.xianglong.flashsale.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final StringRedisTemplate redis;

    /**
     * 固定窗口计数。用 Redis 而非内存计数器（requirement 5.6）。
     */
    public boolean allow(String key, int limit, long windowSeconds) {
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            return false;
        }
        if (count == 1L) {
            redis.expire(key, Duration.ofSeconds(windowSeconds));
        }
        return count <= limit;
    }
}
