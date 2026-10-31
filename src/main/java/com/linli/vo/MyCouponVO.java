package com.linli.vo;

import com.linli.common.TimeText;
import com.linli.entity.UserCoupon;
import java.math.BigDecimal;

/** 我的优惠券列表项，以及领取成功后返回的那一张。 */
public record MyCouponVO(Long id,
                         Long couponId,
                         Long shopId,
                         String shopName,
                         String name,
                         BigDecimal amount,
                         BigDecimal thresholdAmount,
                         Integer status,
                         String statusText,
                         String receiveTime,
                         String expireTime) {

    public static MyCouponVO from(UserCoupon record) {
        if (record == null) return null;
        return new MyCouponVO(record.getId(), record.getCouponId(), record.getShopId(), record.getShopName(),
                record.getName(), record.getAmount(), record.getThresholdAmount(),
                record.getStatus(), record.statusText(),
                TimeText.format(record.getReceiveTime()), TimeText.format(record.getExpireTime()));
    }
}
