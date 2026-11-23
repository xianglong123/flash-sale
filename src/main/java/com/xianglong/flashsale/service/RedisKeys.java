package com.xianglong.flashsale.service;

public final class RedisKeys {
    private RedisKeys() {}

    public static String stock(Long id) { return "seckill:stock:" + id; }
    public static String users(Long id) { return "seckill:user:" + id; }
    public static String meta(Long id) { return "seckill:meta:" + id; }
    public static String recent(Long id) { return "seckill:recent:" + id; }
}
