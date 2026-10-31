package com.linli.service.impl;

import com.linli.common.CacheTtl;
import com.linli.constant.RedisKeyConstant;
import com.linli.entity.Shop;
import com.linli.mapper.ShopMapper;
import com.linli.service.FavoriteService;
import com.linli.service.HistoryService;
import com.linli.vo.ShopVO;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 浏览记录的 ZSet 实现。三条各自解决一个问题：
 *
 * 1. **member 是 shopId，所以重复浏览天然去重**，只会把这条的时间戳往后挪，不会攒出十条同一家店。
 * 2. **写完立刻按 rank 裁到最近 {@value RedisKeyConstant#USER_HISTORY_MAX} 条**
 *    （{@code removeRange(0, -(MAX+1))}：rank 0 是 score 最小也就是最老的一条，负数是倒序计数）。
 *    不裁的话一个活跃用户的键会长到几千个成员，而列表页只要前几条。
 * 3. **键级 TTL 30 天**，每次浏览都往后推。这管的是"用户再也不来"那批键——裁长度管不了它。
 *
 * 和关注 Feed 一样，这里丢了不需要从数据库重建：读不出来就是"还没有浏览记录"，业务照常。
 */
@Service
public class HistoryServiceImpl implements HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryServiceImpl.class);

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final StringRedisTemplate redis;
    private final ShopMapper shopMapper;
    private final FavoriteService favoriteService;

    public HistoryServiceImpl(StringRedisTemplate redis, ShopMapper shopMapper, FavoriteService favoriteService) {
        this.redis = redis;
        this.shopMapper = shopMapper;
        this.favoriteService = favoriteService;
    }

    @Override
    public void record(Long userId, Long shopId) {
        if (userId == null || shopId == null) return;
        String key = RedisKeyConstant.userHistory(userId);
        redis.opsForZSet().add(key, String.valueOf(shopId), System.currentTimeMillis() / 1000.0);
        redis.opsForZSet().removeRange(key, 0, -(RedisKeyConstant.USER_HISTORY_MAX + 1L));
        redis.expire(key, CacheTtl.of(RedisKeyConstant.USER_HISTORY_TTL_SECONDS));
    }

    @Override
    public List<ShopVO> latest(Long userId, int limit) {
        if (userId == null) return List.of();
        int size = limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        Set<String> members = redis.opsForZSet().reverseRange(RedisKeyConstant.userHistory(userId), 0, size - 1L);
        if (members == null || members.isEmpty()) return List.of();
        List<Long> ids = members.stream().map(Long::valueOf).toList();
        Map<Long, Shop> byId = shopMapper.listByIds(ids).stream()
                .collect(Collectors.toMap(Shop::getId, shop -> shop));
        // 保持"最近看过"的顺序：ZSet 已经排好，查回来的整行按 ids 装配，不重排
        List<ShopVO> list = ids.stream().map(byId::get).filter(Objects::nonNull).map(ShopVO::from).toList();
        return favoriteService.withFavorite(userId, list);
    }

    @Override
    public long count(Long userId) {
        if (userId == null) return 0;
        // ZCARD 数的是键里现在有几个成员，和 latest() 的 limit 上限无关：
        // 列表一次最多读 50 条是"界面翻不动"，不是"记录只有 50 条"。
        Long size = redis.opsForZSet().zCard(RedisKeyConstant.userHistory(userId));
        return size == null ? 0L : size;
    }

    @Override
    public void clear(Long userId) {
        if (userId == null) return;
        // 只删键，不先 ZCARD 数条数：为了写一行日志多跑一次 Redis 不值得，条数该由计数接口给
        redis.delete(RedisKeyConstant.userHistory(userId));
        log.debug("浏览记录已清空: userId={}", userId);
    }
}
