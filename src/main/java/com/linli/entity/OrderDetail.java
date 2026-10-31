package com.linli.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** order_detail 表实体。项目名和单价都是下单时的快照，商家改菜单不影响历史订单。 */
@Data
public class OrderDetail {

    private Long id;

    private Long orderId;

    /** 来源套餐 id；取消或超时按这一列回补 goods.stock。 */
    private Long goodsId;

    private Long shopId;

    private String goodsName;

    private String goodsCover;

    private Integer quantity;

    private BigDecimal unitPrice;

    /** 小计 = unitPrice * quantity，由下单方算好写入，读的时候不再算。 */
    private BigDecimal subtotal;

    private LocalDateTime createTime;
}
