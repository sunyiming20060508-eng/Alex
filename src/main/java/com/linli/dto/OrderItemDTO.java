package com.linli.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * POST /api/orders 的明细行。
 *
 * **只有 goodsId 和 quantity 两个字段**：项目名、封面、单价一律由服务端从 goods 表读，
 * 不接受前端传值。之前这版 DTO 带着 goodsName/unitPrice，等于允许客户端自己决定付多少钱。
 *
 * quantity 用注解卡死而不是像以前那样"空当 1、超 99 当 99"地静默纠正：
 * 静默纠正会让前端展示的金额和服务端算出的金额不一致，用户是按哪个付的钱都说不清。
 */
@Data
public class OrderItemDTO {

    @NotNull(message = "缺少套餐 id")
    private Long goodsId;

    @NotNull(message = "请填写套餐数量")
    @Min(value = 1, message = "套餐数量至少为 1")
    @Max(value = 99, message = "单个套餐一次最多购买 99 份")
    private Integer quantity;
}
