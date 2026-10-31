package com.linli.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Ticker;
import com.linli.cache.LocalCache;
import com.linli.common.ApiException;
import com.linli.common.CacheTtlMatcher;
import com.linli.constant.RedisKeyConstant;
import com.linli.context.LoginUser;
import com.linli.context.UserContext;
import com.linli.dto.ShopPageQueryDTO;
import com.linli.entity.Shop;
import com.linli.mapper.FavoriteMapper;
import com.linli.mapper.ShopImageMapper;
import com.linli.mapper.ShopMapper;
import com.linli.service.HistoryService;
import com.linli.vo.OptionVO;
import com.linli.vo.ShopVO;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 商家详情缓存的单元测试（防穿透 + 互斥重建）。这三件事只有在"缓存里是什么"能自由构造时才好测：
 * 空串、脏 JSON、抢不到锁，接口实测里都造不出来或者要等真并发。
 */
@ExtendWith(MockitoExtension.class)
class ShopServiceImplTest {

    private static final Long SHOP_ID = 1L;
    private static final String KEY = RedisKeyConstant.shopDetail(SHOP_ID);
    private static final String LOCK_KEY = KEY + RedisKeyConstant.CACHE_LOCK_SUFFIX;

    @Mock private ShopMapper shopMapper;
    @Mock private ShopImageMapper shopImageMapper;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;
    @Mock private FavoriteMapper favoriteMapper;
    @Mock private HistoryService historyService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private ShopServiceImpl service;

    @BeforeEach
    void setUp() {
        // 收藏用真实现（底下是 mock 的 Mapper）：这样"favorite 不进缓存"这条规则本身被验到，
        // 而不是被一个 mock 掉的 FavoriteService 假装满足。浏览记录只验"有没有被叫到"，用 mock 就够。
        //
        // 一级缓存这里**刻意给一个关掉的实例**：那 16 条用例测的是 Redis 那一层的行为
        // （空值标记、脏值、抢不到锁），开着 L1 的话同一个测试里第二次 detail() 根本不会去问 Redis，
        // 断言和 mock 的严格存根都会变得含混。L1 自己的行为在下面两条专门用例里测。
        service = new ShopServiceImpl(shopMapper, shopImageMapper, redis, objectMapper,
                new FavoriteServiceImpl(favoriteMapper, shopMapper), historyService, new LocalCache(false, 100, Duration.ofSeconds(5), Ticker.systemTicker(), null));
    }

    @Test
    @DisplayName("一级缓存命中时 Redis 一次都不问；开关关掉就是每次都问（A/B 实测靠这个开关）")
    void l1HitSkipsRedis() {
        givenValues();
        when(values.get(KEY)).thenReturn(jsonOfShop());
        ShopServiceImpl withL1 = serviceWithL1();

        withL1.detail(SHOP_ID);
        withL1.detail(SHOP_ID);
        verify(values, times(1)).get(KEY);

        service.detail(SHOP_ID);
        service.detail(SHOP_ID);
        verify(values, times(3)).get(KEY);   // 第 3 次：上面 withL1 那次之后，关掉的这一层各问一次
    }

    @Test
    @DisplayName("改分之后 L1 也必须清掉：只清 Redis 的话本实例还会把旧分再供 5 秒")
    void refreshScoreEvictsL1Too() {
        givenValues();
        when(values.get(KEY)).thenReturn(jsonOfShop());
        ShopServiceImpl withL1 = serviceWithL1();
        withL1.detail(SHOP_ID);
        verify(values, times(1)).get(KEY);

        withL1.refreshScore(SHOP_ID, new BigDecimal("4.6"));

        // 两层都清了，所以再读必须回到 Redis 重新拿一份
        withL1.detail(SHOP_ID);
        verify(values, times(2)).get(KEY);
        verify(redis).delete(KEY);
    }

    private ShopServiceImpl serviceWithL1() {
        return new ShopServiceImpl(shopMapper, shopImageMapper, redis, objectMapper,
                new FavoriteServiceImpl(favoriteMapper, shopMapper), historyService, new LocalCache(true, 100, Duration.ofSeconds(5), Ticker.systemTicker(), null));
    }

    private String jsonOfShop() {
        try {
            return objectMapper.writeValueAsString(shop());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("看详情就交一次浏览记录：匿名交的是 null，留不留由 HistoryService 决定")
    void viewHandsTheSessionUserToHistory() {
        givenValues();
        when(values.get(KEY)).thenReturn(null);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(shopMapper.getById(SHOP_ID)).thenReturn(shop());

        service.detail(SHOP_ID);
        verify(historyService).record(null, SHOP_ID);

        UserContext.set(new LoginUser(7L, "13900000007", "token-7"));
        try {
            service.detail(SHOP_ID);
        } finally {
            UserContext.clear();
        }
        verify(historyService).record(7L, SHOP_ID);
    }

    @Test
    @DisplayName("收藏状态不进缓存：缓存里躺着的是商家实体，登录者与匿名者各拿各的 favorite")
    void favoriteNeverEntersCache() {
        givenValues();
        when(values.get(KEY)).thenReturn(null);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(shopMapper.getById(SHOP_ID)).thenReturn(shop());
        when(favoriteMapper.countOne(7L, SHOP_ID)).thenReturn(1);

        UserContext.set(new LoginUser(7L, "13900000007", "token-7"));
        ShopVO mine;
        try {
            mine = service.detail(SHOP_ID);
        } finally {
            UserContext.clear();
        }
        ShopVO anonymous = service.detail(SHOP_ID);

        assertThat(mine.favorite()).isTrue();
        assertThat(anonymous.favorite()).isFalse();
        ArgumentCaptor<String> cached = ArgumentCaptor.forClass(String.class);
        verify(values, times(2)).set(eq(KEY), cached.capture(), any(Duration.class));
        // 两次写缓存的值都不该含 favorite，否则第一个访问者的状态会被发给所有人
        assertThat(cached.getAllValues()).allSatisfy(json -> assertThat(json).doesNotContain("favorite"));
    }

    @Test
    @DisplayName("缓存命中就直接返回，一次数据库都不查")
    void hitSkipsDatabase() throws Exception {
        givenValues();
        when(values.get(KEY)).thenReturn(objectMapper.writeValueAsString(shop()));

        ShopVO vo = service.detail(SHOP_ID);

        assertThat(vo.name()).isEqualTo("老王面馆");
        verify(shopMapper, never()).getById(any());
        verify(values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("空串代表「这个 id 确认不存在」：假 id 反复打也只走 Redis，不打数据库")
    void emptyValueCacheBlocksPenetration() {
        givenValues();
        when(values.get(KEY)).thenReturn(RedisKeyConstant.CACHE_NULL_VALUE);

        assertFails(() -> service.detail(SHOP_ID), 40401, "商家不存在");
        verify(shopMapper, never()).getById(any());
        // 空值缓存本身已经是答案，不需要再抢重建锁
        verify(values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("未命中且抢到锁：查库、按 30 分钟写缓存、释放锁")
    void missWithLockRebuilds() {
        givenValues();
        when(values.get(KEY)).thenReturn(null);
        when(values.setIfAbsent(LOCK_KEY, "1", Duration.ofSeconds(RedisKeyConstant.CACHE_LOCK_TTL_SECONDS)))
                .thenReturn(true);
        when(shopMapper.getById(SHOP_ID)).thenReturn(shop());

        ShopVO vo = service.detail(SHOP_ID);

        assertThat(vo.id()).isEqualTo(SHOP_ID);
        verify(values).set(eq(KEY), anyString(),
                CacheTtlMatcher.jittered(RedisKeyConstant.SHOP_DETAIL_TTL_SECONDS));
        verify(redis).delete(LOCK_KEY);
    }

    /**
     * Redis 偏偏在"详情已经重建好、只差释放锁"这一刻挂掉。
     * <b>这条不是防 500</b>——外层 {@code catch (RuntimeException)} 早就把缓存层的失败降级成直连库了，
     * 用户无论如何看得到页面。这条防的是**把已经算好的那份结果丢掉再打一次库**：
     * finally 里 delete 抛出 → 外层当成"缓存不可用" → {@code loadFromDb} 又查一遍。
     * 所以断言是"数据库只被问了一次"。反向对照：把 finally 里的 try/catch 摘掉，
     * 这条会变红（getById 被调两次），而不是"照样绿"——第一次跑就是这样，才把"防 500"这个说法纠回来。
     */
    @Test
    @DisplayName("释放锁失败不丢已重建的结果：数据库只被问一次，不该退化成「缓存不可用、再查一遍」")
    void lockReleaseFailureKeepsRebuiltResult() {
        givenValues();
        when(values.get(KEY)).thenReturn(null);
        when(values.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);
        when(shopMapper.getById(SHOP_ID)).thenReturn(shop());
        doThrow(new QueryTimeoutException("redis 回话超时")).when(redis).delete(LOCK_KEY);

        assertThat(service.detail(SHOP_ID).id()).isEqualTo(SHOP_ID);
        verify(shopMapper, times(1)).getById(SHOP_ID);
    }

    @Test
    @DisplayName("库里也确实没有：写 2 分钟空值，别把每次假 id 都变成一次查库")
    void missingShopWritesEmptyMarker() {
        givenValues();
        when(values.get(KEY)).thenReturn(null);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(shopMapper.getById(SHOP_ID)).thenReturn(null);

        assertFails(() -> service.detail(SHOP_ID), 40401, "商家不存在");
        verify(values).set(eq(KEY), eq(RedisKeyConstant.CACHE_NULL_VALUE),
                CacheTtlMatcher.jittered(RedisKeyConstant.SHOP_DETAIL_NULL_TTL_SECONDS));
        verify(redis).delete(LOCK_KEY);
    }

    @Test
    @DisplayName("抢锁失败说明有人在重建：等他把缓存写好，自己不查库、也不删别人的锁")
    void lockLoserWaitsForRebuilder() throws Exception {
        givenValues();
        when(values.get(KEY)).thenReturn(null, objectMapper.writeValueAsString(shop()));
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        ShopVO vo = service.detail(SHOP_ID);

        assertThat(vo.name()).isEqualTo("老王面馆");
        verify(shopMapper, never()).getById(any());
        verify(redis, never()).delete(anyString());
    }

    @Test
    @DisplayName("等不到就自己查库兜一次：宁可多一次查询，也不给用户的请求报错")
    void lockLoserFallsBackToDatabase() {
        givenValues();
        when(values.get(KEY)).thenReturn(null);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(shopMapper.getById(SHOP_ID)).thenReturn(shop());

        assertThat(service.detail(SHOP_ID).name()).isEqualTo("老王面馆");
        verify(values, times(5)).get(KEY);
        verify(redis, never()).delete(anyString());
    }

    @Test
    @DisplayName("缓存里躺着解析不出的脏值要回源重建，不能被当成「这个商家不存在」")
    void corruptCacheValueFallsBackToSource() {
        givenValues();
        when(values.get(KEY)).thenReturn("{ not json");
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(shopMapper.getById(SHOP_ID)).thenReturn(shop());

        assertThat(service.detail(SHOP_ID).id()).isEqualTo(SHOP_ID);
        verify(values).set(eq(KEY), anyString(), any(Duration.class));
    }



    @Test
    @DisplayName("Redis 超时：详情直连数据库，用户不该看到 500")
    void redisTimeoutFallsBackToDatabase() {
        givenValues();
        when(values.get(KEY)).thenThrow(new QueryTimeoutException("command timed out after 500ms"));
        when(shopMapper.getById(SHOP_ID)).thenReturn(shop());

        assertThat(service.detail(SHOP_ID).id()).isEqualTo(SHOP_ID);
        verify(shopMapper).getById(SHOP_ID);
        // 缓存这条路已经不通，别再往上写：多一次写入就多等一个超时
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    /** 回退不是"什么都吞"：空值缓存给出的 404 是一个**答案**，不许再去库里捞一遍。 */
    @Test
    @DisplayName("空值缓存命中抛的 404 不算故障：不回退、不查库")
    void notFoundMarkerIsNotTreatedAsFailure() {
        givenValues();
        when(values.get(KEY)).thenReturn(RedisKeyConstant.CACHE_NULL_VALUE);

        assertFails(() -> service.detail(SHOP_ID), 40401, "商家不存在");
        verify(shopMapper, never()).getById(any());
    }

    @Test
    @DisplayName("抢重建锁时 Redis 不通：同样回退查库，而不是把异常交给用户")
    void lockAcquireFailureFallsBackToDatabase() {
        givenValues();
        when(values.get(KEY)).thenReturn(null);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new QueryTimeoutException("command timed out after 500ms"));
        when(shopMapper.getById(SHOP_ID)).thenReturn(shop());

        assertThat(service.detail(SHOP_ID).id()).isEqualTo(SHOP_ID);
        verify(shopMapper).getById(SHOP_ID);
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    private void givenValues() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    @DisplayName("评价改分必须走这一层：写库 + 顺手删详情缓存，否则新分数要等 30 分钟")
    void refreshScoreWritesAndEvictsCache() {
        service.refreshScore(SHOP_ID, new BigDecimal("4.6"));

        verify(shopMapper).updateScore(SHOP_ID, new BigDecimal("4.6"));
        verify(redis).delete(KEY);
    }

    @Test
    @DisplayName("事务里改分不许当场删缓存：提交前的并发读会用旧值把缓存重建回来，脏满一个 TTL")
    void refreshScoreDefersEvictionUntilCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.refreshScore(SHOP_ID, new BigDecimal("4.6"));

            verify(shopMapper).updateScore(SHOP_ID, new BigDecimal("4.6"));
            verify(redis, never()).delete(anyString());

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(redis).delete(KEY);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("缺参数或分数为 null 时不动库也不动缓存")
    void refreshScoreIgnoresIncompleteInput() {
        service.refreshScore(null, new BigDecimal("4.6"));
        service.refreshScore(SHOP_ID, null);

        verify(shopMapper, never()).updateScore(any(), any());
        verify(redis, never()).delete(anyString());
    }

    @Test
    @DisplayName("sort 白名单外的值直接 40001：悄悄按默认排就是「参数不生效」那个 bug 的成因")
    void unknownSortIsRejected() {
        ShopPageQueryDTO query = new ShopPageQueryDTO();
        query.setSort("distance_meters; drop table shop");

        assertFails(() -> service.page(query), 40001, "sort 只支持 distance、score、price");
        verify(shopMapper, never()).countQuery(any());
    }

    @Test
    @DisplayName("sort 归一后交给 Mapper：大写带空格变小写，没传是 score")
    void sortIsNormalizedBeforeTheQuery() {
        when(shopMapper.countQuery(any())).thenReturn(0L);
        ShopPageQueryDTO upper = new ShopPageQueryDTO();
        upper.setSort("  DISTANCE ");
        service.page(upper);
        assertThat(upper.getSort()).isEqualTo("distance");

        service.page(new ShopPageQueryDTO());
        assertThat(new ShopPageQueryDTO().normalizedSort()).isEqualTo("score");
    }

    @Test
    @DisplayName("下拉选项只带 id 和 name，不去算评分/营业状态那套展示文本")
    void optionsCarryOnlyIdAndName() {
        // Mapper 那条 SQL 只 select 了两列，所以这里构造的实体其余字段本来就是 null；
        // 断言的重点是"Service 没有拿 null 去算出一堆假话（暂无评分 / 营业中）"
        Shop thin = new Shop();
        thin.setId(3L);
        thin.setName("悦己美发");
        Shop another = new Shop();
        another.setId(8L);
        another.setName("星光影院");
        when(shopMapper.listOptions()).thenReturn(List.of(thin, another));

        assertThat(service.options())
                .containsExactly(new OptionVO(3L, "悦己美发"), new OptionVO(8L, "星光影院"));

        // 这一发不碰缓存：它和 /shops 列表不是同一份形状，共用键只会互相失效
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("库里没在售的商家时返回空列表，不是 null——前端 <select> 直接 iterate")
    void optionsEmptyIsNotEmptyList() {
        when(shopMapper.listOptions()).thenReturn(List.of());
        assertThat(service.options()).isEmpty();
    }

    private static Shop shop() {
        Shop shop = new Shop();
        shop.setId(SHOP_ID);
        shop.setName("老王面馆");
        shop.setScore(new BigDecimal("4.8"));
        shop.setMinPrice(20);
        shop.setBusinessHours("09:00-22:00");
        shop.setStatus(1);
        return shop;
    }

    private static void assertFails(Runnable call, int code, String messagePart) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code))
                .hasMessageContaining(messagePart);
    }
}
