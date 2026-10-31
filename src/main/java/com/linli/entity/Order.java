package com.linli.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * orders 表实体。状态常量放在实体里（和苍穹外卖的 Orders 一样），不塞进通用 StatusConstant，
 * 因为那个只管启售/启用这类跨领域开关。
 *
 * 状态机只有 4 个值，刻意不照抄苍穹的 6 个：待接单、已接单、派送中是外卖配送链路的中间态，
 * 本地生活到店消费用不上。所有状态变更都必须走守卫式 UPDATE
 * （{@code where id=? and user_id=? and status in (...)}），不要在 Service 里先查再判再改。
 */
@Data
public class Order {

    /** 待支付。下单后的初始状态，也是超时取消唯一针对的状态。 */
    public static final int STATUS_PENDING_PAYMENT = 1;

    /** 待使用（已支付，等商家核销）。 */
    public static final int STATUS_TO_USE = 2;

    /** 已完成。 */
    public static final int STATUS_COMPLETED = 3;

    /** 已取消（用户主动取消或超时未支付）。 */
    public static final int STATUS_CANCELLED = 4;

    /** 已退款（V15）。只能从"待使用"过来：已核销的单子钱已经消费掉了，退它就是在骗自己。 */
    public static final int STATUS_REFUNDED = 5;

    /** 未支付。 */
    public static final int PAY_UNPAID = 0;

    /** 已支付。 */
    public static final int PAY_PAID = 1;

    /** 已退款。 */
    public static final int PAY_REFUNDED = 2;

    /** 超时取消写进 cancel_reason 的固定文案，和阈值一起放在配置里，别学苍穹写死在代码里。 */
    public static final String TIMEOUT_CANCEL_REASON = "超时未支付，系统自动取消";

    private Long id;

    /** 对外展示的订单号，唯一索引兜底。 */
    private String number;

    /** 团购核销码，到店出示；唯一索引 uk_orders_verify_code 兜底。 */
    private String verifyCode;

    /** 核销码有效期，过期不能再核销。 */
    private LocalDateTime expireTime;

    private Long userId;

    private Long shopId;

    /** 订单原价合计。 */
    private BigDecimal totalAmount;

    /** 券抵扣金额，下单时快照，之后券模板怎么改都不影响这一单。 */
    private BigDecimal discountAmount;

    /** 实付 = totalAmount - discountAmount。 */
    private BigDecimal payAmount;

    private String consignee;

    private String phone;

    private String address;

    private String remark;

    /** 本单核销的用户券 id，null 表示没用券。 */
    private Long userCouponId;

    private Integer status;

    private Integer payStatus;

    /** 支付方式，当前只有 MOCK。 */
    private String payMethod;

    private String cancelReason;

    private LocalDateTime cancelTime;

    private LocalDateTime payTime;

    private LocalDateTime completeTime;

    /** 退款时间（V15）。不能拿 update_time 冒充：任何一次更新都会动它。 */
    private LocalDateTime refundTime;

    /** 下单时间；超时取消按这一列判断，配合 idx_orders_status_create。 */
    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 联表或二次查询带出的商家名，不是 orders 的列。 */
    private String shopName;

    /** 明细，只在详情和列表回填时才有值。 */
    private List<OrderDetail> details;

    /** 展示用文案。状态取不到时返回"未知"而不是抛异常，列表页不该因为一个脏值整体挂掉。 */
    public String statusText() {
        if (status == null) return "未知";
        return switch (status) {
            case STATUS_PENDING_PAYMENT -> "待支付";
            case STATUS_TO_USE -> "待使用";
            case STATUS_COMPLETED -> "已完成";
            case STATUS_CANCELLED -> "已取消";
            case STATUS_REFUNDED -> "已退款";
            default -> "未知";
        };
    }
}
