package com.linli.service;

import com.linli.common.PageResult;
import com.linli.dto.OrderSubmitDTO;
import com.linli.vo.OrderVO;

/** 订单：下单、我的订单分页、详情、支付（当前是 mock）、取消、核销完成、超时关闭。 */
public interface OrderService {

    /**
     * 下单。校验商家与明细、算金额、可选地核销一张用户券，全部在一个事务里。
     *
     * @return 新订单（不含明细行，前端要看得去详情）
     */
    OrderVO submit(OrderSubmitDTO form, Long userId);

    /**
     * 我的订单分页。status 用前端已有的那套词：ALL/UNPAID/UNUSED/DONE/CANCELLED（大小写不敏感），
     * 空或 ALL 表示全部；其它取值抛 40001。数据库里的 1~4 由实现层翻译。
     */
    PageResult<OrderVO> page(int page, int pageSize, String status, Long userId);

    /** 订单详情，含明细。不是本人的订单查不到 → 40401。 */
    OrderVO detail(Long orderId, Long userId);

    /** mock 支付：待支付 → 待使用。重复调用返回 40901。 */
    OrderVO pay(Long orderId, Long userId);

    /** 用户取消：只有待支付能取消；用了券的会把券还回去。已支付要走退款（阶段 5）。 */
    OrderVO cancel(Long orderId, Long userId);

    /**
     * 用户申请退款：待使用 → 已退款。退还占用的库存和优惠券，幂等靠带前置状态的 UPDATE。
     *
     * <p>只有"待使用"能退：待支付本来就没付钱（走取消），已完成是已经消费掉了，
     * 已退款/已取消再退一次会被状态守卫挡成 40901，而不是悄悄成功第二次。
     */
    OrderVO refund(Long orderId, Long userId);

    /** 商家核销：待使用 → 已完成。当前由用户端接口代跑，管理端上线后挪过去。 */
    OrderVO complete(Long orderId, Long userId);

    /**
     * 关闭超时未支付的订单，供定时任务调用。
     *
     * @return 实际关闭的订单数
     */
    int cancelTimeoutOrders(int timeoutMinutes);
}
