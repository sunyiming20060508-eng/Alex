package com.linli.vo;

import com.linli.common.TimeText;
import com.linli.entity.Coupon;
import java.math.BigDecimal;

/** 券列表项（券中心和商家详情页共用）。金额保持 BigDecimal，由契约保证不混用单位。 */
public record CouponVO(Long id,
                       Long shopId,
                       String shopName,
                       String name,
                       BigDecimal amount,
                       BigDecimal thresholdAmount,
                       Integer stock,
                       boolean soldOut,
                       boolean received,
                       String beginTime,
                       String endTime) {

    public static CouponVO from(Coupon coupon) {
        if (coupon == null) return null;
        int stock = coupon.getStock() == null ? 0 : coupon.getStock();
        return new CouponVO(coupon.getId(), coupon.getShopId(), coupon.getShopName(), coupon.getName(),
                coupon.getAmount(), coupon.getThresholdAmount(), stock, stock <= 0,
                Boolean.TRUE.equals(coupon.getReceived()),
                TimeText.format(coupon.getBeginTime()), TimeText.format(coupon.getEndTime()));
    }
}
