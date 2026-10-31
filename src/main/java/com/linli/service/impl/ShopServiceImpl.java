package com.linli.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linli.cache.LocalCache;
import com.linli.common.AfterCommit;
import com.linli.common.ApiException;
import com.linli.common.CacheTtl;
import com.linli.common.ErrorCode;
import com.linli.common.PageResult;
import com.linli.constant.RedisKeyConstant;
import com.linli.dto.ShopPageQueryDTO;
import com.linli.entity.Shop;
import com.linli.mapper.ShopImageMapper;
import com.linli.mapper.ShopMapper;
import com.linli.context.UserContext;
import com.linli.service.FavoriteService;
import com.linli.service.HistoryService;
import com.linli.service.ShopService;
import com.linli.vo.OptionVO;
import com.linli.vo.ShopVO;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 商家查询：分页和详情都走 shop 表，详情用缓存旁路（cache-aside）——先读进程内一级（5 秒），
 * 再读 Redis 二级（TTL 30 分钟 + 抖动），都没有才查库并回写两层。列表因为条件组合多（关键词 + 分类 + 分页）不缓存，
 * 现阶段 8 条数据的 count + limit 足够快，缓存反而要处理失效。
 */
@Service
public class ShopServiceImpl implements ShopService {

    private static final Logger log = LoggerFactory.getLogger(ShopServiceImpl.class);

    private final ShopMapper shopMapper;
    private final ShopImageMapper shopImageMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final FavoriteService favoriteService;
    private final HistoryService historyService;
    private final LocalCache localCache;

    public ShopServiceImpl(ShopMapper shopMapper, ShopImageMapper shopImageMapper, StringRedisTemplate redis,
                           ObjectMapper objectMapper, FavoriteService favoriteService, HistoryService historyService,
                           LocalCache localCache) {
        this.shopMapper = shopMapper;
        this.shopImageMapper = shopImageMapper;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.favoriteService = favoriteService;
        this.historyService = historyService;
        this.localCache = localCache;
    }

    @Override
    public PageResult<ShopVO> page(ShopPageQueryDTO query) {
        ShopPageQueryDTO condition = normalize(query);
        long total = shopMapper.countQuery(condition);
        List<ShopVO> list = total == 0 ? List.of()
                : shopMapper.pageQuery(condition).stream().map(ShopVO::from).toList();
        // 收藏状态是"按人"的，只能在拿到这一页之后填，匿名就是整页 false
        list = favoriteService.withFavorite(UserContext.userIdOrNull(), list);
        return new PageResult<>(list, total, condition.normalizedPage(), condition.normalizedPageSize());
    }

    /**
     * 详情缓存做了三件事：命中返回、**空值缓存防穿透**（不存在的 id 也缓存 2 分钟的空串，
     * 免得有人拿一串假 id 反复打数据库）、**互斥重建防击穿**（热点 key 过期的一瞬间只有一个请求去查库）。
     * 取舍照黑马点评：长尾数据用互斥重建而不是逻辑过期，因为逻辑过期要求预热，商家详情做不到。
     */
    @Override
    public ShopVO detail(Long id) {
        ShopVO vo = detailWithoutViewer(id);
        // 浏览记录在"真的看到了"这条路径上记，所以不放 Controller：详情走缓存命中也算一次浏览
        historyService.record(UserContext.userIdOrNull(), vo.id());
        // 缓存里存的是 Shop 实体，favorite 只能在缓存之外补上：
        // 把它一起缓存，等于把第一个访问者的收藏状态发给之后所有访问者。
        // 图集同理不缓存：它在 shop_image 表里，缓存下来就多一处要和那张表对账的失效点
        return vo.withImages(shopImageMapper.listUrlsByShop(vo.id()))
                .withFavorite(favoriteService.isFavorite(UserContext.userIdOrNull(), vo.id()));
    }

    private ShopVO detailWithoutViewer(Long id) {
        String key = RedisKeyConstant.shopDetail(id);
        // 一级：进程内，省掉一次 Redis 往返 + 一次 JSON 解析。只放与访问者无关的 ShopVO，
        // favorite 和图集仍然在缓存之外按人补（见 detail()）。
        ShopVO inProcess = localCache.get(key);
        if (inProcess != null) {
            log.debug("商家详情一级缓存命中: id={}", id);
            return inProcess;
        }
        try {
            String cached = redis.opsForValue().get(key);
            if (RedisKeyConstant.CACHE_NULL_VALUE.equals(cached)) {
                // 命中空值缓存：这个 id 之前确认过不存在。**不往一级缓存里存"不存在"**，理由见 LocalCache 类注释
                throw new ApiException(ErrorCode.NOT_FOUND, "商家不存在");
            }
            ShopVO hit = toVo(cached);
            if (hit != null) {
                log.debug("商家详情缓存命中: id={}", id);
                localCache.put(key, hit);
                return hit;
            }
            // 走到这里要么是没缓存（null），要么是缓存里躺着一个解析不出的脏值；两种都必须回源，
            // 用新值覆盖脏值。把脏值当成"确认不存在"会让一个真存在的商家永久 404。
            return rebuild(id, key);
        } catch (ApiException e) {
            // 404 是**答案**不是故障：商家确实不存在，这一类必须原样抛出去，不能降级成"查库也许有"
            throw e;
        } catch (RuntimeException e) {
            // 缓存这一层（取、抢锁、写）任何一步失败，都不该让详情页变成 500 —— 数据库才是真相源。
            // 2026-10-28 把 Redis 等待上限从 3s 收到 500ms（WORKLOG S-124），这条兜底让那半秒多数时候不可见。
            // 放进 L1（5 秒）：Redis 长时间不可用时，同一家店的重复访问不再每个请求都等半秒再打库。
            log.warn("商家详情缓存不可用，本次直连数据库: id={} {}", id, e.toString());
            ShopVO fromDb = loadFromDb(id);
            localCache.put(key, fromDb);
            return fromDb;
        }
    }

    /** 不走缓存的回源。和 rebuild() 里的写法保持一致：没有这个 id 就是 404，不是空对象。 */
    private ShopVO loadFromDb(Long id) {
        Shop shop = id == null ? null : shopMapper.getById(id);
        if (shop == null) throw new ApiException(ErrorCode.NOT_FOUND, "商家不存在");
        return ShopVO.from(shop);
    }

    @Override
    public List<String> cities() {
        return shopMapper.listCities();
    }

    /**
     * 下拉框形状： Mapper 那一列只 select 了 id 和 name，所以这里拿到的 Shop 其余字段全是 null。
     * 不复用 {@link ShopVO#from} 是刻意的——那条路径会去算 rating 文本、openStatus、拆 tag，
     * 对着全是 null 的字段算出来的是一堆"暂无评分/营业中"的假话，不如只把两列原样带出去。
     */
    @Override
    public List<OptionVO> options() {
        return shopMapper.listOptions().stream()
                .map(shop -> new OptionVO(shop.getId(), shop.getName()))
                .toList();
    }

    @Override
    public void refreshScore(Long shopId, BigDecimal score) {
        if (shopId == null || score == null) return;
        shopMapper.updateScore(shopId, score);
        // 写路径必须失效缓存：详情缓存 30 分钟，不删的话刚改的分在界面上要等半天才动。
        // 但删除只能发生在**提交之后**：三个调用方（发评价 / 删自己的评价 / 管理端驳回）都在事务里，
        // 提交前删等于给并发读留出"用旧数据重建缓存"的窗口，脏分会挂满一个 TTL。
        // 两层都要清：只清 Redis 的话，本实例的进程内那一层还会把旧分再供 5 秒。
        AfterCommit.run(() -> {
            String key = RedisKeyConstant.shopDetail(shopId);
            redis.delete(key);
            localCache.invalidate(key);
        });
        log.debug("商家展示分已重算: shopId={} score={}", shopId, score);
    }

    private ShopVO rebuild(Long id, String key) {
        String lockKey = key + RedisKeyConstant.CACHE_LOCK_SUFFIX;
        boolean locked = Boolean.TRUE.equals(redis.opsForValue()
                .setIfAbsent(lockKey, "1", Duration.ofSeconds(RedisKeyConstant.CACHE_LOCK_TTL_SECONDS)));
        if (!locked) {
            // 没抢到重建锁：短暂等一下让持锁的那个请求把缓存写好；等不到就自己查库，
            // 宁可多查一次也不让用户的请求失败。
            for (int i = 0; i < 4; i++) {
                sleepQuietly(50);
                ShopVO hit = toVo(redis.opsForValue().get(key));
                if (hit != null) return hit;
            }
        }
        try {
            Shop shop = id == null ? null : shopMapper.getById(id);
            if (shop == null) {
                redis.opsForValue().set(key, RedisKeyConstant.CACHE_NULL_VALUE,
                        CacheTtl.of(RedisKeyConstant.SHOP_DETAIL_NULL_TTL_SECONDS));
                throw new ApiException(ErrorCode.NOT_FOUND, "商家不存在");
            }
            writeCache(key, shop);
            ShopVO built = ShopVO.from(shop);
            localCache.put(key, built);
            return built;
        } finally {
            // 只删自己抢到的锁，等锁那批请求没有删除权。
            // 释放失败必须吞掉。注意它防的**不是 500**——外层那个 catch (RuntimeException) 早就把
            // 缓存层的失败降级成直连库了；这里吞掉异常是为了**别让刚重建好的那份详情被丢掉**：
            // delete 抛出会被外层当成"缓存不可用"，于是又多查一次库（变异测试实测过：断言"只查一次"才测得出来）。
            // 锁本身带 CACHE_LOCK_TTL_SECONDS，不删它也只是"下一批多等一轮"。
            if (locked) {
                try {
                    redis.delete(lockKey);
                } catch (RuntimeException e) {
                    log.warn("商家详情重建锁释放失败，等它自己过期: key={}", lockKey, e);
                }
            }
        }
    }

    /** 缓存值转 VO：空串和解析失败都返回 null，由调用方决定是"确认不存在"还是"回源重建"。 */
    private ShopVO toVo(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return ShopVO.from(objectMapper.readValue(json, Shop.class));
        } catch (JsonProcessingException e) {
            log.warn("商家缓存反序列化失败，按未命中处理: {}", e.getMessage());
            return null;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 把分页参数收进合法区间，并去掉关键词的空格，让 XML 里的 if 判断只看 null。 */
    private ShopPageQueryDTO normalize(ShopPageQueryDTO query) {
        ShopPageQueryDTO condition = query == null ? new ShopPageQueryDTO() : query;
        condition.setKeyword(condition.trimmedKeyword());
        condition.setCity(condition.trimmedCity());
        String sort = condition.normalizedSort();
        if (!ShopPageQueryDTO.SORTS.contains(sort)) {
            throw new ApiException(ErrorCode.PARAM_INVALID, "sort 只支持 distance、score、price");
        }
        // 归一化之后再交给 XML：那里按这个值在三个写死的 order by 分支里选一个
        condition.setSort(sort);
        return condition;
    }

    private void writeCache(String key, Shop shop) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(shop),
                    CacheTtl.of(RedisKeyConstant.SHOP_DETAIL_TTL_SECONDS));
        } catch (JsonProcessingException e) {
            log.warn("商家缓存序列化失败: {}", e.getMessage());
        }
    }
}
