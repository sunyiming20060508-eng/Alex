package com.linli.vo;

import com.linli.entity.Goods;
import java.math.BigDecimal;

/** 商家团购套餐列表项。soldOut 和 discount 都是展示派生值，不落库。 */
public record GoodsVO(Long id,
                      Long shopId,
                      String shopName,
                      String name,
                      String cover,
                      BigDecimal price,
                      BigDecimal originalPrice,
                      String description,
                      Integer stock,
                      boolean soldOut,
                      String discount) {

    public static GoodsVO from(Goods goods) {
        if (goods == null) return null;
        int stock = goods.getStock() == null ? 0 : goods.getStock();
        return new GoodsVO(goods.getId(), goods.getShopId(), goods.getShopName(), goods.getName(),
                goods.getCover(), goods.getPrice(), goods.getOriginalPrice(), goods.getDescription(),
                stock, stock <= 0, goods.discountText());
    }
}
