package com.linli.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** goods 表实体：商家团购套餐。下单只认这里的价格，客户端传什么单价都不算数。 */
@Data
public class Goods {

    private Long id;

    private Long shopId;

    private String name;

    /** 现阶段是表情占位符，和 shop.cover 同一套路。 */
    private String cover;

    /** 团购价。 */
    private BigDecimal price;

    /** 划线原价，只用于展示折扣，不参与任何金额计算。 */
    private BigDecimal originalPrice;

    private String description;

    /** 剩余可售份数。 */
    private Integer stock;

    /** 1 在售，0 下架。 */
    private Integer status;

    private Integer sort;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 联表带出的商家名，不是本表列。 */
    private String shopName;

    public boolean onSale() {
        return status != null && status == 1;
    }

    /** 折扣文案：88/108 → "8.1折"；没有原价或原价更低时返回 null，前端不显示折扣标。 */
    public String discountText() {
        if (originalPrice == null || price == null || originalPrice.signum() <= 0
                || originalPrice.compareTo(price) <= 0) {
            return null;
        }
        BigDecimal rate = price.multiply(BigDecimal.TEN).divide(originalPrice, 1, java.math.RoundingMode.DOWN);
        return rate.stripTrailingZeros().toPlainString() + "折";
    }
}
