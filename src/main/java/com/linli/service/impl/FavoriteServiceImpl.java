package com.linli.service.impl;

import com.linli.common.ApiException;
import com.linli.common.ErrorCode;
import com.linli.common.PageResult;
import com.linli.mapper.FavoriteMapper;
import com.linli.mapper.ShopMapper;
import com.linli.entity.Shop;
import com.linli.service.FavoriteService;
import com.linli.vo.ShopVO;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import com.linli.common.Pagination;

/**
 * 收藏的实现。两个设计点：
 *
 * 1. **幂等靠唯一索引，不靠"先查再插"**。先查再插在并发下会两个请求都查到"没收藏"，然后一个成功
 *    一个撞索引报 500；这里直接插，把 {@link DuplicateKeyException} 翻译成"本来就收藏了"，
 *    和领券、点赞、关注是同一套做法。
 * 2. **列表顺序由 user_favorite 决定，商家整行另查**。先取有序的 shopId 页，再一次 {@code in} 取回整行，
 *    然后按原顺序装配——直接 join 排序会把 Shop 实体的映射写两遍，不值得。
 */
@Service
public class FavoriteServiceImpl implements FavoriteService {

    private static final Logger log = LoggerFactory.getLogger(FavoriteServiceImpl.class);

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;

    private final FavoriteMapper favoriteMapper;
    private final ShopMapper shopMapper;

    public FavoriteServiceImpl(FavoriteMapper favoriteMapper, ShopMapper shopMapper) {
        this.favoriteMapper = favoriteMapper;
        this.shopMapper = shopMapper;
    }

    @Override
    public boolean add(Long userId, Long shopId) {
        // 收藏一个不存在的商家没有意义，而且会在 user_favorite 里留下永远显示不出来的行
        if (shopId == null || shopMapper.getById(shopId) == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "商家不存在");
        }
        try {
            boolean inserted = favoriteMapper.insert(userId, shopId) > 0;
            log.debug("商家已收藏: shopId={} userId={}", shopId, userId);
            return inserted;
        } catch (DuplicateKeyException e) {
            // 不是错误：两个人同时点、或者用户连点两下，第二次的表现就是"本来就收藏着"
            log.debug("重复收藏，由唯一索引兜住: shopId={} userId={}", shopId, userId);
            return false;
        }
    }

    @Override
    public boolean remove(Long userId, Long shopId) {
        boolean removed = shopId != null && favoriteMapper.delete(userId, shopId) > 0;
        log.debug("取消收藏: shopId={} userId={} 命中={}", shopId, userId, removed);
        return removed;
    }

    @Override
    public PageResult<ShopVO> pageMine(Long userId, int page, int pageSize) {
        Pagination query = Pagination.of(page, pageSize, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        int safePage = query.page();
        int safeSize = query.size();
        long total = favoriteMapper.countOfUser(userId);
        if (total == 0) {
            return new PageResult<>(List.of(), 0, safePage, safeSize);
        }
        List<Long> shopIds = favoriteMapper.pageShopIds(userId, (safePage - 1) * safeSize, safeSize);
        if (shopIds.isEmpty()) {
            // 翻过了最后一页：总数还在，但这一页没有行
            return new PageResult<>(List.of(), total, safePage, safeSize);
        }
        Map<Long, Shop> byId = shopMapper.listByIds(shopIds).stream()
                .collect(Collectors.toMap(Shop::getId, shop -> shop));
        // 按 user_favorite 的顺序装配；商家被彻底删掉时跳过，不让一条孤儿收藏拖垮整页
        List<ShopVO> list = shopIds.stream().map(byId::get).filter(java.util.Objects::nonNull)
                .map(shop -> ShopVO.from(shop).withFavorite(true)).toList();
        return new PageResult<>(list, total, safePage, safeSize);
    }

    @Override
    public boolean isFavorite(Long userId, Long shopId) {
        return userId != null && shopId != null && favoriteMapper.countOne(userId, shopId) > 0;
    }

    @Override
    public List<ShopVO> withFavorite(Long userId, List<ShopVO> shops) {
        if (userId == null || shops == null || shops.isEmpty()) return shops == null ? List.of() : shops;
        List<Long> ids = shops.stream().map(ShopVO::id).toList();
        Set<Long> favorites = Set.copyOf(favoriteMapper.filterFavoriteShopIds(userId, ids));
        return shops.stream().map(vo -> vo.withFavorite(favorites.contains(vo.id()))).toList();
    }
}
