package com.linli.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** coupon 表实体：券模板。只做满减券，所以没有 type 列。 */
@Data
public class Coupon {

    private Long id;

    private Long shopId;

    private String name;

    /** 减免金额（元）。 */
    private BigDecimal amount;

    /** 使用门槛（元），0 表示无门槛。 */
    private BigDecimal thresholdAmount;

    /** 剩余库存。扣减一律走 {@code where id=? and stock>0} 的乐观锁。 */
    private Integer stock;

    private LocalDateTime beginTime;

    private LocalDateTime endTime;

    /** 1 上架可领，0 下架。 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 联表带出的商家名（{@code s.name as shop_name}），不是 coupon 表的列。 */
    private String shopName;

    /**
     * 当前登录用户是否已领过，由查询里的 exists 子查询算出（未登录时为 null）。
     * 这是"每次请求实时算"的字段，绝不进任何共享缓存，理由和黑马点评里 isLike 不进缓存一样。
     */
    private Boolean received;

    /** 现在这个时刻是否处于可领取窗口内。 */
    public boolean receivableNow(LocalDateTime now) {
        LocalDateTime at = now == null ? LocalDateTime.now() : now;
        return status != null && status == 1
                && beginTime != null && !at.isBefore(beginTime)
                && endTime != null && !at.isAfter(endTime);
    }
}
