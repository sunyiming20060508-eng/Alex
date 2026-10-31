package com.linli.service;

import com.linli.vo.ShopVO;
import java.util.List;

/**
 * 浏览记录（"最近看过"）。存储是 Redis ZSet，**没有对应的 MySQL 表**：
 * 这份数据 30 天后没有意义，丢了也不影响任何业务判断，所以不值得为它建一张需要定期清理的冷表。
 */
public interface HistoryService {

    /** 记一次浏览。{@code userId} 为 null（匿名）时什么都不做——没有归属的记录将来读不到也没人清。 */
    void record(Long userId, Long shopId);

    /** 最近看过的商家，按浏览时间倒序；商家已被彻底删除的条目会跳过。 */
    List<ShopVO> latest(Long userId, int limit);

    /**
     * 我一共攒了多少条浏览记录。给个人中心的数字用，走 {@code ZCARD}，
     * 所以它和 {@code latest} 的 {@code limit} 上限无关——列表最多一次读 50 条，计数不受这个限制。
     * 匿名和"还没有记录"都回 0，不抛。
     */
    long count(Long userId);

    /** 清空我的浏览记录。用户要能删，这是隐私边界，不是可选功能。 */
    void clear(Long userId);
}
