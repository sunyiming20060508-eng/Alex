package com.linli.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linli.cache.LocalCache;
import com.linli.common.CacheTtl;
import com.linli.constant.RedisKeyConstant;
import com.linli.entity.Category;
import com.linli.mapper.CategoryMapper;
import com.linli.service.CategoryService;
import com.linli.vo.CategoryVO;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 分类基本不变，整份列表缓存 10 分钟（带抖动）；未命中时按互斥锁重建，同一瞬间只有一个请求查库；
 * 重建出来的那份还会进进程内一级缓存，5 秒内的重复读连 Redis 都不用碰。
 */
@Service
public class CategoryServiceImpl implements CategoryService {

    private static final Logger log = LoggerFactory.getLogger(CategoryServiceImpl.class);

    private final CategoryMapper categoryMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final LocalCache localCache;

    public CategoryServiceImpl(CategoryMapper categoryMapper, StringRedisTemplate redis,
                               ObjectMapper objectMapper, LocalCache localCache) {
        this.categoryMapper = categoryMapper;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.localCache = localCache;
    }

    /**
     * 三层各管一段：L1（进程内，5 秒）省一次 Redis 往返 + 一次 JSON 解析；
     * L2（Redis，10 分钟 ± 抖动）省一次查库；互斥重建锁保证一次过期只有一个人去查库。
     *
     * <p>这把锁是 2026-10-27 补的（WORKLOG S-114）。当时预期"100 并发会打出 100 次查询"，
     * 实测旧代码只漏 1~2 次——因为那条 SELECT 服务端平均 0.24 ms，比请求到达的散布还短。
     * 所以锁的说法只能是<b>给最坏情况封顶</b>，不是"省 99 次查询"，公式和证据都在 S-114。
     *
     * <p>依然不做逻辑过期 + 异步重建：那套要求能预热，而这份列表的重建成本就是一条十几行的
     * {@code SELECT}，省下的一点点等待换不回线程池和锁续期的复杂度。
     */
    @Override
    public List<CategoryVO> list() {
        List<Category> inProcess = localCache.get(RedisKeyConstant.CATEGORY_LIST);
        if (inProcess != null) {
            log.debug("分类一级缓存命中，{} 条", inProcess.size());
            return toVo(inProcess);
        }
        try {
            List<Category> cached = readCache();
            if (cached != null) {
                log.debug("分类列表缓存命中，{} 条", cached.size());
                localCache.put(RedisKeyConstant.CATEGORY_LIST, cached);
                return toVo(cached);
            }
            List<Category> rebuilt = rebuild();
            localCache.put(RedisKeyConstant.CATEGORY_LIST, rebuilt);
            return toVo(rebuilt);
        } catch (RuntimeException e) {
            // Redis 超时/断连不该把"能靠数据库回答"的读接口变成 500。2026-10-28 把等待上限从
            // 3s 收到 500ms（WORKLOG S-124）之后，这条兜底让那半秒对用户大部分时候是不可见的：
            // 分类在库里只有一条 SELECT，直接查比让用户对着报错页重试便宜得多。
            // 顺手放进 L1（5 秒），Redis 长时间不可用时每个 5 秒只有一次请求打库，而不是每个请求都打。
            // 这不是"猜"：库里那份就是真值，缓存本来就是它的副本。
            log.warn("分类缓存不可用，本次直连数据库: {}", e.toString());
            List<Category> fromDb = categoryMapper.listEnabled();
            localCache.put(RedisKeyConstant.CATEGORY_LIST, fromDb);
            return toVo(fromDb);
        }
    }

    private static List<CategoryVO> toVo(List<Category> categories) {
        return categories.stream().map(CategoryVO::from).toList();
    }

    /** 抢到锁的那个查库并写缓存；没抢到的等 50ms 再看一眼，最多四次，等不到就自己查。 */
    private List<Category> rebuild() {
        String key = RedisKeyConstant.CATEGORY_LIST;
        String lockKey = key + RedisKeyConstant.CACHE_LOCK_SUFFIX;
        boolean locked = Boolean.TRUE.equals(redis.opsForValue()
                .setIfAbsent(lockKey, "1", Duration.ofSeconds(RedisKeyConstant.CACHE_LOCK_TTL_SECONDS)));
        if (!locked) {
            // 等锁期间不报错也不让用户看到空列表：宁可多查一次，也不能把一次缓存过期变成一次故障
            for (int i = 0; i < 4; i++) {
                sleepQuietly(50);
                List<Category> hit = readCache();
                if (hit != null) return hit;
            }
        }
        try {
            List<Category> categories = categoryMapper.listEnabled();
            writeCache(categories);
            return categories;
        } finally {
            // 只删自己抢到的锁，等锁那批请求没有删除权。
            // 释放失败必须吞掉。注意它防的**不是 500**——外层那个 catch (RuntimeException) 早就把
            // 缓存层的失败降级成直连库了；这里吞掉异常是为了**别让刚重建好的那份列表被丢掉**：
            // delete 抛出会被外层当成"缓存不可用"，于是 listEnabled() 又多查一次（断言"只查一次"才测得出来）。
            // 锁本身带 CACHE_LOCK_TTL_SECONDS 的过期时间，不删它也只是"下一批多等一轮"。
            if (locked) {
                try {
                    redis.delete(lockKey);
                } catch (RuntimeException e) {
                    log.warn("分类重建锁释放失败，等它自己过期: key={}", lockKey, e);
                }
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<Category> readCache() {
        String json = redis.opsForValue().get(RedisKeyConstant.CATEGORY_LIST);
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<List<Category>>() {});
        } catch (JsonProcessingException e) {
            // 脏值同时作废 L1：不然下一次读又会把它从 L2 搬回 L1，白删一次
            localCache.invalidate(RedisKeyConstant.CATEGORY_LIST);
            log.warn("分类缓存反序列化失败，删除 {}: {}", RedisKeyConstant.CATEGORY_LIST, e.getMessage());
            redis.delete(RedisKeyConstant.CATEGORY_LIST);
            return null;
        }
    }

    private void writeCache(List<Category> categories) {
        try {
            redis.opsForValue().set(RedisKeyConstant.CATEGORY_LIST,
                    objectMapper.writeValueAsString(categories),
                    CacheTtl.of(RedisKeyConstant.CATEGORY_LIST_TTL_SECONDS));
        } catch (JsonProcessingException e) {
            log.warn("分类缓存序列化失败: {}", e.getMessage());
        }
    }
}
