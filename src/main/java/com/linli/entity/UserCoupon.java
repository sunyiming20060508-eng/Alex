package com.linli.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * user_coupon 表实体：用户已领取的券。
 *
 * name/amount/thresholdAmount/expireTime 都是**领取那一刻的快照**，不是外键引用 coupon。
 * 运营之后改券面额或下架，都不能改变用户手里已经领到的券，所以这几列不能省成一次 join。
 */
@Data
public class UserCoupon {

    /** 1 未使用。 */
    public static final int STATUS_UNUSED = 1;

    /** 2 已使用。 */
    public static final int STATUS_USED = 2;

    /** 3 已过期。 */
    public static final int STATUS_EXPIRED = 3;

    private Long id;

    private Long userId;

    private Long couponId;

    private Long shopId;

    private String name;

    private BigDecimal amount;

    private BigDecimal thresholdAmount;

    private Integer status;

    private LocalDateTime receiveTime;

    private LocalDateTime expireTime;

    private LocalDateTime useTime;

    /** 核销它的订单 id，下单前为 null。 */
    private Long orderId;

    /** 联表带出的商家名，不是本表的列。 */
    private String shopName;

    /**
     * 展示用状态：数据库里 status=1 但已过到期时间的行，不依赖定时任务也要显示成"已过期"。
     * 真正的 status 翻转留给阶段 3 后面的定时任务做。
     */
    public String statusText() {
        if (status == null) return "未知";
        if (status == STATUS_USED) return "已使用";
        if (status == STATUS_EXPIRED) return "已过期";
        if (status == STATUS_UNUSED && expireTime != null && expireTime.isBefore(LocalDateTime.now())) {
            return "已过期";
        }
        return "未使用";
    }
}
