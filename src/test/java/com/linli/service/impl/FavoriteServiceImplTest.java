package com.linli.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.linli.common.ApiException;
import com.linli.common.PageResult;
import com.linli.entity.Shop;
import com.linli.mapper.FavoriteMapper;
import com.linli.mapper.ShopMapper;
import com.linli.vo.ShopVO;
import java.math.BigDecimal;
import java.util.List;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * 收藏的单元测试。守的是"幂等"这件事到底由谁保证：如果实现是"先查再插"，
 * 这里能测出重复调用只发一次插入；而并发下真正兜住的是唯一索引抛出的冲突被翻译成 false，不是 500。
 */
@ExtendWith(MockitoExtension.class)
class FavoriteServiceImplTest {

    private static final Long USER = 7L;
    private static final Long SHOP = 2L;

    @Mock private FavoriteMapper favoriteMapper;
    @Mock private ShopMapper shopMapper;

    private final FavoriteServiceImpl service() {
        return new FavoriteServiceImpl(favoriteMapper, shopMapper);
    }

    @Test
    @DisplayName("收藏一家不存在的商家：404，且不会往关系表里插一行显示不出来的数据")
    void addRejectsUnknownShop() {
        when(shopMapper.getById(SHOP)).thenReturn(null);

        assertThatThrownBy(() -> service().add(USER, SHOP))
                .isInstanceOf(ApiException.class).hasMessage("商家不存在");
        verifyNoInteractions(favoriteMapper);
    }

    @Test
    @DisplayName("第一次收藏真的插入，返回 true")
    void addInsertsOnce() {
        when(shopMapper.getById(SHOP)).thenReturn(shop());
        when(favoriteMapper.insert(USER, SHOP)).thenReturn(1);

        assertThat(service().add(USER, SHOP)).isTrue();
    }

    @Test
    @DisplayName("重复收藏撞唯一索引：回 false（幂等），不冒 500")
    void duplicateFavoriteIsIdempotent() {
        when(shopMapper.getById(SHOP)).thenReturn(shop());
        when(favoriteMapper.insert(USER, SHOP)).thenThrow(new DuplicateKeyException("uk_favorite_user_shop"));

        assertThat(service().add(USER, SHOP)).isFalse();
    }

    @Test
    @DisplayName("取消没收藏过的店是 no-op：删 0 行回 false，不报错")
    void removeIsIdempotentToo() {
        when(favoriteMapper.delete(USER, SHOP)).thenReturn(0);

        assertThat(service().remove(USER, SHOP)).isFalse();
        verify(favoriteMapper).delete(USER, SHOP);
    }

    @Test
    @DisplayName("我的收藏按 user_favorite 的顺序装配，商家被彻底删掉的那条跳过而不是整页报错")
    void pageMineKeepsFavoriteOrderAndSkipsOrphans() {
        when(favoriteMapper.countOfUser(USER)).thenReturn(3L);
        when(favoriteMapper.pageShopIds(USER, 0, 10)).thenReturn(List.of(3L, 2L, 99L));
        when(shopMapper.listByIds(List.of(3L, 2L, 99L))).thenReturn(List.of(shop(2L), shop(3L)));

        PageResult<ShopVO> page = service().pageMine(USER, 1, 10);

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.list()).extracting(ShopVO::id).containsExactly(3L, 2L);
        assertThat(page.list()).allSatisfy(vo -> assertThat(vo.favorite()).isTrue());
    }

    @Test
    @DisplayName("翻过最后一页：总数还在，这一页是空的")
    void pageBeyondLastPageReturnsEmptyList() {
        when(favoriteMapper.countOfUser(USER)).thenReturn(2L);
        when(favoriteMapper.pageShopIds(USER, 20, 10)).thenReturn(List.of());

        PageResult<ShopVO> page = service().pageMine(USER, 3, 10);

        assertThat(page.list()).isEmpty();
        assertThat(page.total()).isEqualTo(2);
        verify(shopMapper, never()).listByIds(anyList());
    }

    @Test
    @DisplayName("一页商家一次 IN 回填 favorite，不在循环里逐条问")
    void fillUsesOneQueryPerPage() {
        List<ShopVO> shops = List.of(ShopVO.from(shop(1L)), ShopVO.from(shop(2L)), ShopVO.from(shop(3L)));
        when(favoriteMapper.filterFavoriteShopIds(eq(USER), anyList())).thenReturn(List.of(2L));

        List<ShopVO> filled = service().withFavorite(USER, shops);

        assertThat(filled).extracting(ShopVO::id, ShopVO::favorite)
                .containsExactly(Tuple.tuple(1L, false),
                        Tuple.tuple(2L, true),
                        Tuple.tuple(3L, false));
        verify(favoriteMapper).filterFavoriteShopIds(USER, List.of(1L, 2L, 3L));
    }

    @Test
    @DisplayName("匿名浏览一页商家：一条 SQL 都不发，整页 favorite=false")
    void anonymousSkipsTheQuery() {
        List<ShopVO> shops = List.of(ShopVO.from(shop(1L)));

        assertThat(service().withFavorite(null, shops)).isSameAs(shops);
        assertThat(service().isFavorite(null, SHOP)).isFalse();
        verifyNoInteractions(favoriteMapper);
    }

    private static Shop shop() {
        return shop(SHOP);
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
