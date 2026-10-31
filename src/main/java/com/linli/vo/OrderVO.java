package com.linli.vo;

import com.linli.common.TimeText;
import com.linli.entity.Order;
import com.linli.entity.OrderDetail;
import java.math.BigDecimal;
import java.util.List;

/** 订单返回对象。列表页 details 为空数组，详情页才带明细。 */
public record OrderVO(Long id,
                      String number,
                      String verifyCode,
                      String expireTime,
                      Long shopId,
                      String shopName,
                      BigDecimal totalAmount,
                      BigDecimal discountAmount,
                      BigDecimal payAmount,
                      String consignee,
                      String phone,
                      String address,
                      String remark,
                      Integer status,
                      String statusText,
                      Integer payStatus,
                      String payMethod,
                      String cancelReason,
                      String payTime,
                      String cancelTime,
                      String completeTime,
                      /** 退款时间。库里单独有一列，就是为了不被 update_time 冒充。 */
                      String refundTime,
                      String createTime,
                      List<Detail> details,
                      boolean reviewed) {

    /** 订单明细行。 */
    public record Detail(Long id, Long goodsId, String goodsName, String goodsCover, Integer quantity,
                         BigDecimal unitPrice, BigDecimal subtotal) {

        public static Detail from(OrderDetail detail) {
            return new Detail(detail.getId(), detail.getGoodsId(), detail.getGoodsName(), detail.getGoodsCover(),
                    detail.getQuantity(), detail.getUnitPrice(), detail.getSubtotal());
        }
    }

    public static OrderVO from(Order order) {
        return from(order, List.of());
    }

    public static OrderVO from(Order order, List<OrderDetail> details) {
        if (order == null) return null;
        List<Detail> lines = details == null ? List.of() : details.stream().map(Detail::from).toList();
        return new OrderVO(order.getId(), order.getNumber(), order.getVerifyCode(),
                TimeText.format(order.getExpireTime()), order.getShopId(), order.getShopName(),
                order.getTotalAmount(), order.getDiscountAmount(), order.getPayAmount(),
                order.getConsignee(), order.getPhone(), order.getAddress(), order.getRemark(),
                order.getStatus(), order.statusText(), order.getPayStatus(), order.getPayMethod(),
                order.getCancelReason(),
                TimeText.format(order.getPayTime()), TimeText.format(order.getCancelTime()),
                TimeText.format(order.getCompleteTime()), TimeText.format(order.getRefundTime()),
                TimeText.format(order.getCreateTime()), lines, false);
    }

    /**
     * 这一单是否已经评价过。**只有列表接口会填**（一次 {@code in} 查询算出一页的），
     * 详情和下单响应里恒为 false——和 {@code details} 只在详情里填是对称的取舍：
     * 谁需要这个字段，谁才付那次查询。前端「去评价」按钮以列表里的值为准。
     */
    public OrderVO withReviewed(boolean value) {
        return new OrderVO(id, number, verifyCode, expireTime, shopId, shopName, totalAmount, discountAmount,
                payAmount, consignee, phone, address, remark, status, statusText, payStatus, payMethod,
                cancelReason, payTime, cancelTime, completeTime, refundTime, createTime, details, value);
    }
}
