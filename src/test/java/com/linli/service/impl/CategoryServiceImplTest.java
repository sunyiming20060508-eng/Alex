package com.linli.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.linli.common.CacheTtlMatcher;
import com.linli.constant.RedisKeyConstant;
import com.linli.entity.Category;
import com.linli.mapper.CategoryMapper;
import com.linli.vo.CategoryVO;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 分类列表缓存的单元测试。这一组专门盯"过期那一刻的并发"：
 * 抢锁、等锁、等不到自己查——这三条路只有把 Redis 的返回值随意指派才测得到，
 * 接口实测要等真并发才能撞出来（本机 100 并发实测旧代码只漏 1~2 次回库：那条 SELECT 服务端
 * 平均 0.24ms，比请求到达的散布还短，见 docs/WORKLOG.md S-114 的公式）。
 */
@ExtendWith(MockitoExtension.class)
class CategoryServiceImplTest {

    private static final String KEY = RedisKeyConstant.CATEGORY_LIST;
    private static final String LOCK_KEY = KEY + RedisKeyConstant.CACHE_LOCK_SUFFIX;

    @Mock private CategoryMapper categoryMapper;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private CategoryServiceImpl service;

    /** 真实现、开着的一级缓存：默认 5 秒，单个测试跑不完这么久，所以它就是"这一轮里第二次读会命中 L1"。 */
    private LocalCache localCache;

    @BeforeEach
    void setUp() {
        localCache = new LocalCache(true, 100, Duration.ofSeconds(5), Ticker.systemTicker(), null);
        service = new CategoryServiceImpl(categoryMapper, redis, objectMapper, localCache);
    }

    private void givenValues() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    @DisplayName("Redis 超时：分类直接从库回答，用户看不到 500")
    void redisTimeoutFallsBackToDatabase() {
        givenValues();
        when(values.get(KEY)).thenThrow(new QueryTimeoutException("command timed out after 500ms"));
        when(categoryMapper.listEnabled()).thenReturn(List.of(category(1, "美食"), category(2, "休闲")));

        List<CategoryVO> list = service.list();

        assertThat(list).hasSize(2);
        verify(categoryMapper).listEnabled();
        // 缓存这条路已经不通了，就不要再往上写：写一次要再等一个超时，纯属给请求加时间
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }

    /** 反向对照的另一半：取缓存正常、抢重建锁时 Redis 才断的，也必须回退而不是抛给用户。 */
    @Test
    @DisplayName("抢重建锁时 Redis 不通：同样回退查库")
    void lockFailureFallsBackToDatabase() {
        givenValues();
        when(values.get(KEY)).thenReturn(null);
        when(values.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class)))
                .thenThrow(new QueryTimeoutException("command timed out after 500ms"));
        when(categoryMapper.listEnabled()).thenReturn(List.of(category(1, "美食")));

        assertThat(service.list()).hasSize(1);
        verify(categoryMapper).listEnabled();
    }

    private static Category category(long id, String name) {
        Category c = new Category();
        c.setId(id);
        c.setName(name);
        c.setSort((int) id);
        c.setStatus(1);
        return c;
    }



    private String jsonOf(List<Category> list) throws Exception {
        return objectMapper.writeValueAsString(list);
    }

    @Test
    @DisplayName("命中缓存：一次库都不查，这本来就是缓存的全部意义")
    void hitSkipsDatabase() throws Exception {
        givenValues();
        when(values.get(KEY)).thenReturn(jsonOf(List.of(category(1, "美食"), category(2, "休闲"))));

        List<CategoryVO> list = service.list();

        assertThat(list).extracting(CategoryVO::name).containsExactly("美食", "休闲");
        verifyNoInteractions(categoryMapper);
    }

    @Test
    @DisplayName("第二次读连 Redis 都不碰：一级缓存命中，省掉的是一趟网络加一次 JSON 解析")
    void secondReadSkipsRedisEntirely() throws Exception {
        givenValues();
        when(values.get(KEY)).thenReturn(jsonOf(List.of(category(1, "美食"))));

        List<CategoryVO> first = service.list();
        List<CategoryVO> second = service.list();

        assertThat(second).extracting(CategoryVO::name).containsExactly("美食");
        assertThat(first).extracting(CategoryVO::name).containsExactly("美食");
        // 关键断言：Redis 只被问过这一次。去掉 L1 这层，这里会变成 2 次。
        verify(values, times(1)).get(KEY);
    }

    @Test
    @DisplayName("开关关掉时行为退回单层 Redis：两次读就是两次 GET（A/B 实测靠的就是这个开关）")
    void disabledFallsBackToRedisOnly() throws Exception {
        givenValues();
        when(values.get(KEY)).thenReturn(jsonOf(List.of(category(1, "美食"))));
        CategoryServiceImpl withoutL1 =
                new CategoryServiceImpl(categoryMapper, redis, objectMapper, new LocalCache(false, 100, Duration.ofSeconds(5), Ticker.systemTicker(), null));

        withoutL1.list();
        withoutL1.list();

        verify(values, times(2)).get(KEY);
    }

    @Test
    @DisplayName("未命中且抢到重建锁：查一次库、写回缓存（带 TTL）、只删自己那把锁")
    void lockHolderRebuildsAndReleases() {
        givenValues();
        when(values.get(KEY)).thenReturn(null);
        when(values.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);
        when(categoryMapper.listEnabled()).thenReturn(List.of(category(1, "美食")));

        List<CategoryVO> list = service.list();

        assertThat(list).hasSize(1);
        verify(categoryMapper, times(1)).listEnabled();
        verify(values).set(eq(KEY), anyString(), CacheTtlMatcher.jittered(RedisKeyConstant.CATEGORY_LIST_TTL_SECONDS));
        // 反向对照：重建锁的 5 秒是**业务时长**不是缓存寿命，绝不能被抖动传染——
        // 锁提前失效等于把击穿窗口重新打开
        verify(values).setIfAbsent(eq(LOCK_KEY), anyString(), eq(Duration.ofSeconds(RedisKeyConstant.CACHE_LOCK_TTL_SECONDS)));
        verify(redis).delete(LOCK_KEY);
    }

    /**
     * Redis 偏偏在"列表已经重建好、只差释放锁"这一刻挂掉。
     * <b>这条不是防 500</b>——外层那个 {@code catch (RuntimeException)} 早就把缓存层失败降级成直连库了。
     * 这条防的是**把已经重建好的那份列表丢掉、再查一遍库**：finally 里的 delete 抛出会被外层当成
     * "缓存不可用"，于是 {@code listEnabled()} 被调第二次。所以断言是"数据库只被问了一次"。
     * 第一次跑变异（把 try/catch 摘掉）时这条**居然是绿的**，正因为原来的断言只看"返回对不对"，
     * 而降级路径返回的一样对——是这条红不了的事实把"能防 500"这个说法纠回来的。
     */
    @Test
    @DisplayName("释放锁失败不丢已重建的列表：数据库只被问一次，不该退化成「缓存不可用、再查一遍」")
    void lockReleaseFailureKeepsRebuiltList() {
        givenValues();
        when(values.get(KEY)).thenReturn(null);
        when(values.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);
        when(categoryMapper.listEnabled()).thenReturn(List.of(category(1, "美食")));
        doThrow(new QueryTimeoutException("redis 回话超时")).when(redis).delete(LOCK_KEY);

        assertThat(service.list()).extracting(CategoryVO::name).containsExactly("美食");
        verify(categoryMapper, times(1)).listEnabled();
    }

    /**
     * 这一条就是"防击穿"本身：没抢到锁的请求等 50ms 看到持锁者写好的缓存，直接用它。
     * 反向对照——把这段逻辑删掉（回到裸 read-through），这条必红，因为每个并发都会去查库。
     */
    @Test
    @DisplayName("没抢到锁：等一等，缓存被别人写好就不再查库——一次过期只让一个请求回库")
    void lockLoserWaitsAndReuses() throws Exception {
        givenValues();
        // 第一次读是空的（所以进了重建分支），等了一轮之后读到了持锁者刚写进去的值
        when(values.get(KEY)).thenReturn(null, jsonOf(List.of(category(1, "美食"))));
        when(values.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(false);

        List<CategoryVO> list = service.list();

        assertThat(list).extracting(CategoryVO::name).containsExactly("美食");
        verifyNoInteractions(categoryMapper);
    }

    @Test
    @DisplayName("等满四轮还是没等到：自己查库兜底，一次缓存过期不能变成一次故障")
    void lockLoserFallsBackToDatabase() {
        givenValues();
        when(values.get(KEY)).thenReturn(null);
        when(values.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(false);
        when(categoryMapper.listEnabled()).thenReturn(List.of(category(1, "美食"), category(2, "休闲")));

        List<CategoryVO> list = service.list();

        assertThat(list).hasSize(2);
        verify(categoryMapper).listEnabled();
        // 没抢到锁就没有删除权：把别人正拿着的锁删掉，等于把击穿窗口重新打开
        verify(redis, never()).delete(LOCK_KEY);
    }

    @Test
    @DisplayName("缓存里躺着脏 JSON：删掉键、回源重建，不能把\"解析不出\"当成\"没有分类\"")
    void dirtyValueIsEvictedNotCachedAsEmpty() {
        givenValues();
        when(values.get(KEY)).thenReturn("{ not json", null, null, null, null, null);
        when(values.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);
        when(categoryMapper.listEnabled()).thenReturn(List.of(category(1, "美食")));

        List<CategoryVO> list = service.list();

        assertThat(list).hasSize(1);
        verify(redis).delete(KEY);
        verify(values).set(eq(KEY), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("库里真的没有分类：空列表照样写进缓存，免得每个请求都来问一次数据库")
    void emptyListIsAlsoCached() {
        givenValues();
        when(values.get(KEY)).thenReturn(null);
        when(values.setIfAbsent(eq(LOCK_KEY), anyString(), any(Duration.class))).thenReturn(true);
        when(categoryMapper.listEnabled()).thenReturn(List.of());

        assertThat(service.list()).isEmpty();

        verify(values).set(eq(KEY), eq("[]"), CacheTtlMatcher.jittered(RedisKeyConstant.CATEGORY_LIST_TTL_SECONDS));
        verify(redis).delete(LOCK_KEY);
    }
}
