package com.linli.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

/**
 * POST /api/orders 的入参。联系人和地址是**下单时快照**进订单的（本项目还没有地址簿表，
 * 阶段 5 有了之后仍然保留这几列，避免历史订单去 join 一张会被改会被删的表）。
 *
 * 这里的长度上限和 orders 表列宽一一对应：超长的值若在 Service 里才发现，
 * MySQL 会直接截断或报 data too long，回到前端是 50001；用注解拦在入口才能给出 40001 文案。
 */
@Data
public class OrderSubmitDTO {

    /** 单笔订单的明细行数上限，同时用于注解和提示文案（注解属性需要编译期常量）。 */
    public static final int MAX_ITEMS = 20;

    @NotNull(message = "缺少商家 id")
    private Long shopId;

    @NotEmpty(message = "订单至少要选一个套餐")
    @Size(max = MAX_ITEMS, message = "单笔订单最多 " + MAX_ITEMS + " 个套餐")
    @Valid
    private List<OrderItemDTO> items;

    /** 用哪张用户券，null 表示不用券。 */
    private Long userCouponId;

    @Size(max = 50, message = "联系人姓名最多 50 个字符")
    private String consignee;

    /** 允许空串：这几项都是可选的，空白按"没填"处理，和 Service 里 trimToNull 的语义对齐。 */
    @Pattern(regexp = "^$|^1\\d{10}$", message = "联系电话格式不正确")
    private String phone;

    @Size(max = 255, message = "地址最多 255 个字符")
    private String address;

    @Size(max = 255, message = "备注最多 255 个字符")
    private String remark;

    /**
     * 幂等键，由客户端生成（一次下单意图一个 UUID），网络超时后重试复用同一个。
     * 可选：不传就是普通下单，后端不去重。带上来之后重复提交拿到的是同一张订单，
     * 库存也只扣一次——前端 {@code placing} 只能挡同页面的连点，挡不了超时重试。
     */
    @Size(max = 64, message = "requestId 最多 64 个字符")
    private String requestId;
}
