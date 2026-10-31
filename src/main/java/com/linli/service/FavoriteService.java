package com.linli.service;

import com.linli.common.PageResult;
import com.linli.vo.ShopVO;
import java.util.List;

/**
 * 商家收藏。关系本身由 {@code uk_favorite_user_shop} 保证一人一店一条，
 * 所以"收藏"和"取消收藏"两个接口都是幂等的：重复调用不会报错，也不会多出行了。
 */
public interface FavoriteService {

    /** 收藏一家店。@return true 是这次真加了一条，false 是本来就收藏过（幂等） */
    boolean add(Long userId, Long shopId);

    /** 取消收藏。@return true 是删掉了一条，false 是本来就没收藏 */
    boolean remove(Long userId, Long shopId);

    /** 我的收藏，按收藏时间倒序。 */
    PageResult<ShopVO> pageMine(Long userId, int page, int pageSize);

    /** 单条判断，给商家详情用；{@code userId} 为 null（匿名）时恒 false。 */
    boolean isFavorite(Long userId, Long shopId);

    /** 给一页商家回填 favorite：一次 IN 查完，匿名直接原样返回。 */
    List<ShopVO> withFavorite(Long userId, List<ShopVO> shops);
}
