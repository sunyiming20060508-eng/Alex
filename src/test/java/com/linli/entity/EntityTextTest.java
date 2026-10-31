package com.linli.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 实体里那两个"展示口径"方法：折扣文案和状态文案。它们不进数据库，错了只会让页面难看，但要要住规则。 */
class EntityTextTest {

    @Test
    @DisplayName("折扣按 团购价/原价 取一位小数且向下取整，不给用户报高折扣")
    void discountText() {
        assertThat(goods("88", "108").discountText()).isEqualTo("8.1折");
        // 8.19 向下取整成 8.1，而不是四舍五入成 8.2：折扣标宁可写小也不写大
        assertThat(goods("81.9", "100").discountText()).isEqualTo("8.1折");
        assertThat(goods("50", "100").discountText()).isEqualTo("5折");
        assertThat(goods("9", "10").discountText()).isEqualTo("9折");
    }

    @Test
    @DisplayName("没有原价、原价不大于团购价、原价为 0：都不显示折扣标")
    void discountTextNeedsAHigherOriginalPrice() {
        assertThat(goods("88", null).discountText()).isNull();
        assertThat(goods("88", "88").discountText()).isNull();
        assertThat(goods("88", "60").discountText()).isNull();
        assertThat(goods("88", "0").discountText()).isNull();
        assertThat(goods(null, "108").discountText()).isNull();
    }

    @Test
    @DisplayName("状态文案四个都有，脏值回「未知」而不是抛异常——列表页不该为一个坏值整体挂掉")
    void orderStatusText() {
        assertThat(orderStatus(Order.STATUS_PENDING_PAYMENT).statusText()).isEqualTo("待支付");
        assertThat(orderStatus(Order.STATUS_TO_USE).statusText()).isEqualTo("待使用");
        assertThat(orderStatus(Order.STATUS_COMPLETED).statusText()).isEqualTo("已完成");
        assertThat(orderStatus(Order.STATUS_CANCELLED).statusText()).isEqualTo("已取消");
        assertThat(orderStatus(99).statusText()).isEqualTo("未知");
        assertThat(orderStatus(null).statusText()).isEqualTo("未知");
    }

    @Test
    @DisplayName("下架只认 status=1，脏值 null 也算下架（宁可不卖）")
    void onSaleRequiresExplicitOne() {
        assertThat(new Goods().onSale()).isFalse();

        Goods onSale = new Goods();
        onSale.setStatus(1);
        assertThat(onSale.onSale()).isTrue();

        Goods off = new Goods();
        off.setStatus(0);
        assertThat(off.onSale()).isFalse();
    }

    private static Goods goods(String price, String originalPrice) {
        Goods goods = new Goods();
        goods.setPrice(price == null ? null : new BigDecimal(price));
        goods.setOriginalPrice(originalPrice == null ? null : new BigDecimal(originalPrice));
        goods.setStatus(1);
        return goods;
    }

    private static Order orderStatus(Integer status) {
        Order order = new Order();
        order.setStatus(status);
        order.setCreateTime(LocalDateTime.now());
        return order;
    }
}
