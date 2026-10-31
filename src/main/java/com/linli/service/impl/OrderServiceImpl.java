package com.linli.service.impl;

import com.linli.common.ApiException;
import com.linli.common.ErrorCode;
import com.linli.common.PageResult;
import com.linli.constant.RedisKeyConstant;
import com.linli.dto.OrderItemDTO;
import com.linli.dto.OrderPageQueryDTO;
import com.linli.dto.OrderSubmitDTO;
import com.linli.entity.Goods;
import com.linli.entity.Order;
import com.linli.entity.OrderDetail;
import com.linli.entity.UserCoupon;
import com.linli.mapper.GoodsMapper;
import com.linli.mapper.OrderDetailMapper;
import com.linli.mapper.OrderMapper;
import com.linli.mapper.ReviewMapper;
import com.linli.mapper.ShopMapper;
import com.linli.mapper.UserCouponMapper;
import com.linli.service.OrderService;
import com.linli.vo.OrderVO;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.linli.common.Text;

/**
 * 订单。写路径遵守三条规矩：
 *
 * 1. **金额一律服务端算**。下单只接受 goodsId + 数量，名称、封面、单价都从 goods 表读；
 *    客户端传的价格不参与计算（之前那版接受前端传 unitPrice，等于允许自己决定付多少钱）。
 * 2. **状态变更只走守卫式 UPDATE**（{@code where id=? and user_id=? and status in (...)}），
 *    影响行数 0 就是失败。校验与写入合成一步，重复支付、重复取消、越权都表现为"改不动"，
 *    且不泄露"这单是不是别人的"。
 * 3. **金额和商品信息是下单时的快照**：原价合计、抵扣、实付、明细名称与单价都落库，
 *    之后套餐改价或下架都不影响历史订单。
 * 4. **重复提交靠客户端幂等键去重**（{@code requestId} + Redis SETNX）：网络超时后重试拿到的是
 *    同一张订单，库存也只扣一次。前端的 {@code placing} 只能挡同一个页面的连点。
 *
 * 用了券就在一单里核销掉，取消或超时关闭再把券还回去、把套餐库存回补，
 * 否则用户的券和商家的库存会被一张没成交的订单吃掉。
 */
@Service
public class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private static final String PAY_METHOD_MOCK = "MOCK";
    private static final DateTimeFormatter NUMBER_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OrderMapper orderMapper;
    private final OrderDetailMapper orderDetailMapper;
    private final UserCouponMapper userCouponMapper;
    private final GoodsMapper goodsMapper;
    private final ShopMapper shopMapper;
    private final ReviewMapper reviewMapper;
    private final StringRedisTemplate redis;

    /** 核销码有效期天数，团购券的常规做法；不写死，方便演示和对接真实业务时调整。 */
    @Value("${linli.order.valid-days:30}")
    private int validDays;

    public OrderServiceImpl(OrderMapper orderMapper, OrderDetailMapper orderDetailMapper,
                            UserCouponMapper userCouponMapper, GoodsMapper goodsMapper,
                            ShopMapper shopMapper, ReviewMapper reviewMapper, StringRedisTemplate redis) {
        this.orderMapper = orderMapper;
        this.orderDetailMapper = orderDetailMapper;
        this.userCouponMapper = userCouponMapper;
        this.goodsMapper = goodsMapper;
        this.shopMapper = shopMapper;
        this.reviewMapper = reviewMapper;
        this.redis = redis;
    }

    @Override
    @Transactional
    public OrderVO submit(OrderSubmitDTO form, Long userId) {
        String requestId = trimToNull(form.getRequestId());
        if (requestId == null) return createOrder(form, userId);

        // 幂等键用 SETNX 占位：一次往返就能判出"这是第一次"还是"重复提交"。
        // 值先是 PENDING，成功后换成订单 id；Redis 调用落在数据库事务里，
        // 以这个量级不构成问题，换来的是"超时重试不会再扣一次库存"。
        String key = RedisKeyConstant.orderRequest(userId, requestId);
        Boolean first = redis.opsForValue().setIfAbsent(key, RedisKeyConstant.ORDER_REQUEST_PENDING,
                Duration.ofSeconds(RedisKeyConstant.ORDER_REQUEST_TTL_SECONDS));
        if (!Boolean.TRUE.equals(first)) return replay(key, userId);
        try {
            OrderVO created = createOrder(form, userId);
            redis.opsForValue().set(key, String.valueOf(created.id()),
                    Duration.ofSeconds(RedisKeyConstant.ORDER_REQUEST_TTL_SECONDS));
            return created;
        } catch (RuntimeException | Error e) {
            // 单没建成就把键删掉，同一个 requestId 还能重试；不删的话用户只能刷新换意图
            redis.delete(key);
            throw e;
        }
    }

    /** 重复提交：上一次已经成单就把那张单原样回给你，还在进行中就让你稍等。 */
    private OrderVO replay(String key, Long userId) {
        String value = redis.opsForValue().get(key);
        if (value == null) {
            throw new ApiException(ErrorCode.CONFLICT, "上一次提交已结束，请重新下单");
        }
        if (RedisKeyConstant.ORDER_REQUEST_PENDING.equals(value)) {
            throw new ApiException(ErrorCode.CONFLICT, "订单正在提交中，请稍后到订单列表查看");
        }
        Long orderId;
        try {
            orderId = Long.valueOf(value);
        } catch (NumberFormatException e) {
            orderId = null;
        }
        // 仍然按 userId 查，不让幂等键变成越权读别人订单的入口
        Order order = orderId == null ? null : orderMapper.getByIdAndUser(orderId, userId);
        if (order == null) {
            // 键里躺着一个查不到的 id：事务最终回滚了但键没清掉，清掉让重试真正执行
            redis.delete(key);
            throw new ApiException(ErrorCode.CONFLICT, "上一次提交未完成，请重新下单");
        }
        log.info("下单幂等重放: userId={} orderId={} key={}", userId, orderId, key);
        return OrderVO.from(order, orderDetailMapper.listByOrderIds(List.of(order.getId())));
    }

    private OrderVO createOrder(OrderSubmitDTO form, Long userId) {
        // shopId/items/goodsId/quantity/phone 的格式校验已在 DTO 注解上，由 Controller 的 @Valid 触发；
        // 这里只剩"业务规则"级别的判断：商家在不在、套餐归不属于这家、库存够不够、券能不能用。
        List<OrderItemDTO> items = form.getItems();
        if (shopMapper.getById(form.getShopId()) == null) throw new ApiException(ErrorCode.NOT_FOUND, "商家不存在");

        List<OrderDetail> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        // 一次 IN 查询把要买的套餐全读回来：原来在循环里逐个 getById，三个套餐就是三次往返
        // （WORKLOG S-135 量到的基线 2N+1）。**只有读被合并**——扣减仍然一条一句，
        // 因为那句 UPDATE 的条件 stock >= quantity 就是防超卖的地方，折成一条 case when 语句
        // 会让"哪个套餐没抢到"说不清，也等于把守卫从数据库搬到 Java 里重算一遍。
        Map<Long, Goods> goodsById = new HashMap<>();
        for (Goods found : goodsMapper.listByIds(
                items.stream().map(OrderItemDTO::getGoodsId).distinct().toList())) {
            goodsById.put(found.getId(), found);
        }
        for (OrderItemDTO item : items) {
            Goods goods = goodsById.get(item.getGoodsId());
            if (goods == null) throw new ApiException(ErrorCode.NOT_FOUND, "套餐不存在");
            if (!goods.onSale()) throw new ApiException(ErrorCode.CONFLICT, "「" + goods.getName() + "」已下架");
            if (!form.getShopId().equals(goods.getShopId())) {
                throw new ApiException(ErrorCode.PARAM_INVALID, "「" + goods.getName() + "」不属于该商家");
            }
            int quantity = item.getQuantity();
            // 扣减交给带条件的 UPDATE，这里只为了提前给出友好文案；真正的防超卖在 deductStock
            int stock = goods.getStock() == null ? 0 : goods.getStock();
            if (stock < quantity) {
                throw new ApiException(ErrorCode.CONFLICT,
                        stock <= 0 ? "「" + goods.getName() + "」已售罄"
                                    : "「" + goods.getName() + "」库存不足，仅剩 " + stock + " 份");
            }
            if (goodsMapper.deductStock(goods.getId(), quantity) == 0) {
                throw new ApiException(ErrorCode.CONFLICT, "「" + goods.getName() + "」刚刚被抢完了");
            }
            BigDecimal unitPrice = money(goods.getPrice());
            OrderDetail line = new OrderDetail();
            line.setGoodsId(goods.getId());
            line.setShopId(goods.getShopId());
            line.setGoodsName(goods.getName());
            line.setGoodsCover(goods.getCover());
            line.setQuantity(quantity);
            line.setUnitPrice(unitPrice);
            line.setSubtotal(money(unitPrice.multiply(BigDecimal.valueOf(quantity))));
            total = total.add(line.getSubtotal());
            lines.add(line);
        }

        String consignee = trimToNull(form.getConsignee());
        String phone = trimToNull(form.getPhone());

        UserCoupon coupon = null;
        BigDecimal discount = BigDecimal.ZERO;
        if (form.getUserCouponId() != null) {
            coupon = userCouponMapper.getByIdAndUser(form.getUserCouponId(), userId);
            if (coupon == null) throw new ApiException(ErrorCode.NOT_FOUND, "优惠券不存在");
            if (!Integer.valueOf(UserCoupon.STATUS_UNUSED).equals(coupon.getStatus())) {
                throw new ApiException(ErrorCode.CONFLICT, "这张券已经用过了");
            }
            if (coupon.getExpireTime() != null && coupon.getExpireTime().isBefore(LocalDateTime.now())) {
                throw new ApiException(ErrorCode.CONFLICT, "这张券已过期");
            }
            if (!form.getShopId().equals(coupon.getShopId())) {
                throw new ApiException(ErrorCode.CONFLICT, "这张券不属于该商家");
            }
            if (coupon.getThresholdAmount() != null && total.compareTo(coupon.getThresholdAmount()) < 0) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "订单金额未达到该券门槛（满 " + coupon.getThresholdAmount().toPlainString() + " 可用）");
            }
            // 抵扣不能超过订单金额：无门槛券面额大于消费额时，只抵到刚好清零
            discount = money(coupon.getAmount() == null ? BigDecimal.ZERO : coupon.getAmount().min(total));
        }

        LocalDateTime now = LocalDateTime.now();
        Order order = new Order();
        order.setNumber(nextNumber());
        order.setVerifyCode(nextVerifyCode());
        order.setExpireTime(now.plusDays(validDays <= 0 ? 30 : validDays));
        order.setUserId(userId);
        order.setShopId(form.getShopId());
        order.setTotalAmount(money(total));
        order.setDiscountAmount(money(discount));
        order.setPayAmount(money(total.subtract(discount)));
        order.setUserCouponId(coupon == null ? null : coupon.getId());
        order.setConsignee(consignee);
        order.setPhone(phone);
        order.setAddress(trimToNull(form.getAddress()));
        order.setRemark(trimToNull(form.getRemark()));
        order.setStatus(Order.STATUS_PENDING_PAYMENT);
        order.setPayStatus(Order.PAY_UNPAID);
        order.setCreateTime(now);
        orderMapper.insert(order);

        lines.forEach(line -> line.setOrderId(order.getId()));
        orderDetailMapper.insertBatch(lines);

        if (coupon != null && userCouponMapper.markUsed(coupon.getId(), userId, order.getId()) == 0) {
            // 并发下同一张券被另一单抢先核销；抛异常让本单整体回滚（扣掉的套餐库存也一起回滚）
            throw new ApiException(ErrorCode.CONFLICT, "这张券刚刚已被使用，请重新下单");
        }
        // 核销码不进日志：它是"到店凭以核销"的凭证，等于卡号；订单号已经足够定位这一单。
        log.info("下单成功: number={} userId={} shopId={} total={} discount={} pay={}",
                order.getNumber(), userId, order.getShopId(),
                order.getTotalAmount(), order.getDiscountAmount(), order.getPayAmount());
        return OrderVO.from(order, lines);
    }

    @Override
    public PageResult<OrderVO> page(int page, int pageSize, String status, Long userId) {
        OrderPageQueryDTO condition = new OrderPageQueryDTO();
        condition.setPage(page);
        condition.setPageSize(pageSize);
        condition.setStatus(statusCode(status));
        condition.setUserId(userId);
        long total = orderMapper.countQuery(condition);
        if (total == 0) {
            return new PageResult<>(List.of(), 0, condition.normalizedPage(), condition.normalizedPageSize());
        }
        List<Order> orders = orderMapper.pageQuery(condition);
        List<Long> ids = orders.stream().map(Order::getId).toList();
        // 本页明细一次取回再分组，避免"每单再查一次"的 N+1
        Map<Long, List<OrderDetail>> linesByOrder = orderDetailMapper.listByOrderIds(ids).stream()
                .collect(Collectors.groupingBy(OrderDetail::getOrderId));
        // 评过没有也要一次问完，前端据此决定已完成的那几张卡片亮不亮「去评价」
        Set<Long> reviewed = ids.isEmpty() ? Set.of() : new HashSet<>(reviewMapper.listReviewedOrderIds(userId, ids));
        List<OrderVO> list = orders.stream()
                .map(order -> OrderVO.from(order, linesByOrder.getOrDefault(order.getId(), List.of()))
                        .withReviewed(reviewed.contains(order.getId())))
                .toList();
        return new PageResult<>(list, total, condition.normalizedPage(), condition.normalizedPageSize());
    }

    @Override
    public OrderVO detail(Long orderId, Long userId) {
        Order order = requireOrder(orderId, userId);
        return OrderVO.from(order, orderDetailMapper.listByOrderIds(List.of(order.getId())));
    }

    @Override
    @Transactional
    public OrderVO pay(Long orderId, Long userId) {
        Order order = requireOrder(orderId, userId);
        Order patch = new Order();
        patch.setId(order.getId());
        patch.setStatus(Order.STATUS_TO_USE);
        patch.setPayStatus(Order.PAY_PAID);
        patch.setPayMethod(PAY_METHOD_MOCK);
        patch.setPayTime(LocalDateTime.now());
        assertUpdated(orderMapper.updateStatusWithGuard(patch, userId, List.of(Order.STATUS_PENDING_PAYMENT)),
                "该订单当前状态无法支付");
        return detail(order.getId(), userId);
    }

    @Override
    @Transactional
    public OrderVO cancel(Long orderId, Long userId) {
        Order order = requireOrder(orderId, userId);
        Order patch = new Order();
        patch.setId(order.getId());
        patch.setStatus(Order.STATUS_CANCELLED);
        patch.setCancelReason("用户取消");
        patch.setCancelTime(LocalDateTime.now());
        int updated = orderMapper.updateStatusWithGuard(patch, userId, List.of(Order.STATUS_PENDING_PAYMENT));
        if (updated == 0) {
            boolean paid = Integer.valueOf(Order.STATUS_TO_USE).equals(order.getStatus());
            throw new ApiException(ErrorCode.CONFLICT,
                    paid ? "已支付的订单请先申请退款" : "该订单当前状态无法取消");
        }
        if (order.getUserCouponId() != null) {
            userCouponMapper.releaseByOrderIds(List.of(order.getId()), Order.STATUS_CANCELLED);
        }
        goodsMapper.restoreStockByOrderIds(List.of(order.getId()), Order.STATUS_CANCELLED);
        return detail(order.getId(), userId);
    }

    @Override
    @Transactional
    public OrderVO refund(Long orderId, Long userId) {
        Order order = requireOrder(orderId, userId);
        Order patch = new Order();
        patch.setId(order.getId());
        patch.setStatus(Order.STATUS_REFUNDED);
        patch.setRefundTime(LocalDateTime.now());
        // 和取消同一套：改状态那条 UPDATE 自己带前置状态，"是不是待使用"不靠先查一遍来判。
        // 先查出来的 order 只用来决定要不要还券，而那一步必须等守卫成功之后再做。
        int updated = orderMapper.updateStatusWithGuard(patch, userId, List.of(Order.STATUS_TO_USE));
        if (updated == 0) {
            throw new ApiException(ErrorCode.CONFLICT, refundRefusal(order.getStatus()));
        }
        if (order.getUserCouponId() != null) {
            userCouponMapper.releaseByOrderIds(List.of(order.getId()), Order.STATUS_REFUNDED);
        }
        goodsMapper.restoreStockByOrderIds(List.of(order.getId()), Order.STATUS_REFUNDED);
        log.debug("订单已退款: orderId={} userId={} 退还券={}", orderId, userId, order.getUserCouponId() != null);
        return detail(order.getId(), userId);
    }

    /** 退不了的原因按当前状态说清楚——只回一句"状态不允许"等于让用户自己猜哪一步错了。 */
    private static String refundRefusal(Integer status) {
        if (status == null) return "该订单当前状态无法退款";
        return switch (status) {
            case Order.STATUS_PENDING_PAYMENT -> "这单还没付款，直接取消就行";
            case Order.STATUS_COMPLETED -> "已经消费过了，退不了";
            case Order.STATUS_REFUNDED -> "这单已经退过款了";
            case Order.STATUS_CANCELLED -> "已取消的订单不用再退款";
            default -> "该订单当前状态无法退款";
        };
    }

    @Override
    @Transactional
    public OrderVO complete(Long orderId, Long userId) {
        Order order = requireOrder(orderId, userId);
        if (order.getExpireTime() != null && order.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new ApiException(ErrorCode.CONFLICT, "核销码已过有效期，无法核销");
        }
        Order patch = new Order();
        patch.setId(order.getId());
        patch.setStatus(Order.STATUS_COMPLETED);
        patch.setCompleteTime(LocalDateTime.now());
        assertUpdated(orderMapper.updateStatusWithGuard(patch, userId, List.of(Order.STATUS_TO_USE)),
                "只有待使用的订单可以核销完成");
        return detail(order.getId(), userId);
    }

    @Override
    @Transactional
    public int cancelTimeoutOrders(int timeoutMinutes) {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(timeoutMinutes);
        List<Long> ids = orderMapper.listTimeoutIds(deadline, Order.STATUS_PENDING_PAYMENT);
        if (ids.isEmpty()) return 0;
        int cancelled = orderMapper.cancelByIds(ids, Order.TIMEOUT_CANCEL_REASON,
                Order.STATUS_PENDING_PAYMENT, Order.STATUS_CANCELLED);
        if (cancelled == 0) return 0;
        // 这些单子可能占着券和套餐库存，一起还回去；两处都只还"确实被这批单取消掉了"的部分
        userCouponMapper.releaseByOrderIds(ids, Order.STATUS_CANCELLED);
        goodsMapper.restoreStockByOrderIds(ids, Order.STATUS_CANCELLED);
        log.info("超时未支付订单已关闭 {} 单，阈值 {} 分钟", cancelled, timeoutMinutes);
        return cancelled;
    }

    /** 前端 tab 用的是词，库里存的是数字，翻译只发生在这里。 */
    private static Integer statusCode(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status.trim())) return null;
        return switch (status.trim().toUpperCase()) {
            case "UNPAID" -> Order.STATUS_PENDING_PAYMENT;
            case "UNUSED" -> Order.STATUS_TO_USE;
            case "DONE" -> Order.STATUS_COMPLETED;
            case "CANCELLED" -> Order.STATUS_CANCELLED;
            case "REFUNDED" -> Order.STATUS_REFUNDED;
            default -> throw new ApiException(ErrorCode.PARAM_INVALID,
                    "status 只支持 ALL/UNPAID/UNUSED/DONE/CANCELLED/REFUNDED");
        };
    }

    private Order requireOrder(Long orderId, Long userId) {
        Order order = orderId == null ? null : orderMapper.getByIdAndUser(orderId, userId);
        if (order == null) throw new ApiException(ErrorCode.NOT_FOUND, "订单不存在");
        return order;
    }

    private static void assertUpdated(int updated, String message) {
        if (updated == 0) throw new ApiException(ErrorCode.CONFLICT, message);
    }

    /** 时间戳 + 4 位随机数，够单人场景不撞号；真撞了由 uk_orders_number 拦下，不做重试。 */
    private static String nextNumber() {
        return "L" + LocalDateTime.now().format(NUMBER_STAMP) + String.format("%04d", RANDOM.nextInt(10000));
    }

    /** 10 位数字核销码，扫码/口报都好念；撞号由 uk_orders_verify_code 拦下。 */
    private static String nextVerifyCode() {
        return String.format("%010d", RANDOM.nextInt(1_000_000_000));
    }

    private static String trimToNull(String value) {
        return Text.trimToNull(value);
    }

    /**
     * 金额一律规整到两位小数。入参经 Jackson 会先过 double，"88.00" 进来可能变成标度 1 的 88.0，
     * 不规整就会出现"下单响应里 304.0、之后 GET 同一单 304.00"这种同一资源两个表示。
     */
    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
