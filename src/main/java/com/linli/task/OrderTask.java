package com.linli.task;

import com.linli.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 订单相关定时任务。抄苍穹外卖的做法：不引入 MQ 死信队列，就用一个 cron 扫超时未支付，
 * 关闭动作是一条带前置状态守卫的批量 UPDATE。单实例下不需要分布式调度防重；
 * 将来扩实例要么换 XXL-JOB/ShedLock，要么把 where 条件当成天然幂等（已取消的不会再被扫到）。
 */
@Component
public class OrderTask {

    private static final Logger log = LoggerFactory.getLogger(OrderTask.class);

    private final OrderService orderService;

    /** 支付超时时长（分钟）。阈值和取消文案都参数化，不学苍穹把"1 小时"写死在提示语里。 */
    @Value("${linli.order.pay-timeout-minutes:5}")
    private int payTimeoutMinutes;

    public OrderTask(OrderService orderService) { this.orderService = orderService; }

    /** 每分钟一次。cron 口径：秒 分 时 日 月 周。 */
    @Scheduled(cron = "0 * * * * ?")
    public void cancelTimeoutOrders() {
        int cancelled = orderService.cancelTimeoutOrders(payTimeoutMinutes);
        if (cancelled > 0) {
            log.info("本轮关闭超时未支付订单 {} 单，阈值 {} 分钟", cancelled, payTimeoutMinutes);
        }
    }
}
