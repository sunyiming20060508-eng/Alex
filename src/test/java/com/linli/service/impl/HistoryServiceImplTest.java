package com.linli.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.linli.common.CacheTtlMatcher;
import com.linli.constant.RedisKeyConstant;
import com.linli.entity.Shop;
import com.linli.mapper.ShopMapper;
import com.linli.service.FavoriteService;
import com.linli.vo.ShopVO;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

/**
 * 浏览记录的单元测试。ZSet 的区间参数在阶段 4 翻过一次车（把 {@code (min,max)} 写成倒序，
 * 结果永远读空，而 {@code anyDouble()} 的 stub 完全无感），所以这里凡是边界都用 eq() 写死具体值。
 */
@ExtendWith(MockitoExtension.class)
class HistoryServiceImplTest {

    private static final Long USER = 7L;
    private static final String KEY = RedisKeyConstant.userHistory(USER);

    @Mock private StringRedisTemplate redis;
    @Mock private ZSetOperations<String, String> zSet;
    @Mock private ShopMapper shopMapper;
    @Mock private FavoriteService favoriteService;

    private HistoryServiceImpl service() {
        return new HistoryServiceImpl(redis, shopMapper, favoriteService);
    }

    @Test
    @DisplayName("匿名和缺 shopId 都不写：没有归属的记录将来读不到也没人清")
    void skipsAnonymous() {
        service().record(null, 3L);
        service().record(USER, null);

        verifyNoInteractions(zSet);
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("记一次浏览 = 加分 + 裁到最近 200 条 + 续 30 天 TTL")
    void recordsAndTrims() {
        when(redis.opsForZSet()).thenReturn(zSet);

        service().record(USER, 3L);

        verify(zSet).add(eq(KEY), eq("3"), anyDouble());
        // rank 0 是 score 最小（最老）的一条，-(MAX+1) 即 -201：保留最新 200 条，其余裁掉
        verify(zSet).removeRange(KEY, 0, -(RedisKeyConstant.USER_HISTORY_MAX + 1L));
        verify(redis).expire(eq(KEY), CacheTtlMatcher.jittered(RedisKeyConstant.USER_HISTORY_TTL_SECONDS));
    }

    @Test
    @DisplayName("读出来保持 ZSet 的\"最近优先\"顺序，即使数据库按别的顺序回行")
    void keepsRecencyOrder() {
        when(redis.opsForZSet()).thenReturn(zSet);
        when(zSet.reverseRange(KEY, 0, 9)).thenReturn(new LinkedHashSet<>(List.of("3", "2")));
        when(shopMapper.listByIds(List.of(3L, 2L))).thenReturn(List.of(shop(2L), shop(3L)));
        when(favoriteService.withFavorite(eq(USER), anyList())).thenAnswer(call -> call.getArgument(1));

        List<ShopVO> list = service().latest(USER, 10);

        assertThat(list).extracting(ShopVO::id).containsExactly(3L, 2L);
        verify(favoriteService).withFavorite(eq(USER), anyList());
    }

    @Test
    @DisplayName("商家被彻底删掉时跳过那条，不让一个孤儿 member 变成 null 卡片")
    void skipsShopsThatNoLongerExist() {
        when(redis.opsForZSet()).thenReturn(zSet);
        when(zSet.reverseRange(KEY, 0, 9)).thenReturn(new LinkedHashSet<>(List.of("3", "99")));
        when(shopMapper.listByIds(List.of(3L, 99L))).thenReturn(List.of(shop(3L)));
        when(favoriteService.withFavorite(eq(USER), anyList())).thenAnswer(call -> call.getArgument(1));

        assertThat(service().latest(USER, 10)).extracting(ShopVO::id).containsExactly(3L);
    }

    @Test
    @DisplayName("没有记录就一次数据库都不查")
    void emptyHistorySkipsDatabase() {
        when(redis.opsForZSet()).thenReturn(zSet);
        when(zSet.reverseRange(KEY, 0, 9)).thenReturn(Set.of());

        assertThat(service().latest(USER, 10)).isEmpty();
        verifyNoInteractions(shopMapper);
    }

    @Test
    @DisplayName("limit 归一：0 走默认 10，超上限压到 50")
    void normalizesLimit() {
        when(redis.opsForZSet()).thenReturn(zSet);
        when(zSet.reverseRange(KEY, 0, 9)).thenReturn(Set.of());
        when(zSet.reverseRange(KEY, 0, 49)).thenReturn(Set.of());

        service().latest(USER, 0);
        service().latest(USER, 999);

        verify(zSet).reverseRange(KEY, 0, 9);
        verify(zSet).reverseRange(KEY, 0, 49);
    }

    @Test
    @DisplayName("计数走 ZCARD：不受 latest 那个 50 条上限影响，也不顺手读一遍列表")
    void countsWholeSet() {
        when(redis.opsForZSet()).thenReturn(zSet);
        when(zSet.zCard(KEY)).thenReturn(137L);

        assertThat(service().count(USER)).isEqualTo(137L);
        verify(zSet, never()).reverseRange(any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("连接层回 null 也算 0 条：一个计数读不到不该把空指针抛到界面上")
    void nullSizeMeansZero() {
        when(redis.opsForZSet()).thenReturn(zSet);
        when(zSet.zCard(KEY)).thenReturn(null);

        assertThat(service().count(USER)).isZero();
    }

    @Test
    @DisplayName("匿名读浏览记录：空列表，Redis 一次都不碰")
    void anonymousReadTouchesNothing() {
        assertThat(service().latest(null, 10)).isEmpty();
        assertThat(service().count(null)).isZero();
        service().clear(null);

        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("清空就是删键：用户说不要了，得真没有")
    void clearDeletesTheKey() {
        service().clear(USER);

        verify(redis).delete(KEY);
        verify(redis, never()).expire(eq(KEY), any());
    }

    private static Shop shop(Long id) {
        Shop shop = new Shop();
        shop.setId(id);
        shop.setName("老王面馆" + id);
        shop.setScore(new BigDecimal("4.8"));
        shop.setStatus(1);
        return shop;
    }
}
