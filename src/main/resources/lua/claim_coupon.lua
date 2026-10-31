-- 领券快速通道：库存判断 + 一人一券判断 + 扣减 + 登记，全部在 Redis 单线程内原子完成。
-- KEYS[1] = life:coupon:stock:{couponId}
-- KEYS[2] = life:coupon:claimed:{couponId}
-- ARGV[1] = userId
-- ARGV[2] = 键的存活秒数（Java 按"距券到期时间 + 余量"算好传进来；<=0 表示不设 TTL）
-- 返回 0=已扣减并登记  1=库存不足  2=已领过  3=键未初始化
--
-- 第 3 种返回是刻意加的：黑马点评那份脚本直接 tonumber(redis.call('get', stockKey))，
-- 键不存在时 tonumber(nil) 会在脚本里报错。这里先判 false，让 Java 侧从数据库把库存预热回来再重试。
local stock = redis.call('get', KEYS[1])
if (stock == false) then
    return 3
end
if (tonumber(stock) <= 0) then
    return 1
end
if (redis.call('sismember', KEYS[2], ARGV[1]) == 1) then
    return 2
end

redis.call('decrby', KEYS[1], 1)
redis.call('sadd', KEYS[2], ARGV[1])

-- 兜底 TTL：预热时只给库存键设了过期时间，判重集合是这里 sadd 才第一次创建的，
-- 没有 TTL 就会在券下架后永久留一批键。ttl 返回 -1 表示"键存在但没设过期"。
local ttl = tonumber(ARGV[2])
if (ttl > 0) then
    if (redis.call('ttl', KEYS[1]) < 0) then
        redis.call('expire', KEYS[1], ttl)
    end
    if (redis.call('ttl', KEYS[2]) < 0) then
        redis.call('expire', KEYS[2], ttl)
    end
end

return 0
