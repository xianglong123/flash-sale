-- KEYS[1] = seckill:stock:{id}
-- KEYS[2] = seckill:user:{id}
-- ARGV[1] = userId
-- 返回: 1=扣减成功  0=售罄  -1=已参与  -2=未预热
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -1
end
local stock = redis.call('GET', KEYS[1])
if not stock then
    return -2
end
if tonumber(stock) <= 0 then
    return 0
end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 1
