package com.linli.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.linli.common.ApiException;
import com.linli.constant.RedisKeyConstant;
import com.linli.dto.OrderItemDTO;
import com.linli.dto.OrderSubmitDTO;
import com.linli.entity.Goods;
import com.linli.entity.Order;
import com.linli.entity.OrderDetail;
import com.linli.entity.Shop;
import com.linli.entity.UserCoupon;
import com.linli.mapper.GoodsMapper;
import com.linli.mapper.OrderDetailMapper;
import com.linli.mapper.OrderMapper;
import com.linli.mapper.ReviewMapper;
import com.linli.mapper.ShopMapper;
import com.linli.mapper.UserCouponMapper;
import com.linli.vo.OrderVO;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 下单与状态机的单元测试。Mapper 全部 mock：这里要证明的是 Service 的决策——金额谁说了算、
 * 券能不能用、状态能不能过去、失败要不要还库存。SQL 本身由接口实测覆盖。
 *
 * 三条必须守住的底线，都是"前端改一下就能绕过"的地方：
 * 单价只能从 goods 表读、抵扣不超过订单金额、状态变更只认守卫式 UPDATE 的影响行数。
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    private static final Long USER_ID = 4L;
    private static final Long SHOP_ID = 1L;
    private static final Long ORDER_ID = 100L;

    @Mock private OrderMapper orderMapper;
    @Mock private OrderDetailMapper orderDetailMapper;
    @Mock private UserCouponMapper userCouponMapper;
    @Mock private GoodsMapper goodsMapper;
    @Mock private ShopMapper shopMapper;
    @Mock private ReviewMapper reviewMapper;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;

    private OrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OrderServiceImpl(orderMapper, orderDetailMapper, userCouponMapper, goodsMapper, shopMapper,
                reviewMapper, redis);
        ReflectionTestUtils.setField(service, "validDays", 30);
    }

    @Test
    @DisplayName("金额由服务端算：单价取自 goods 表，实付等于合计减抵扣，两位小数")
    void pricesComeFromDatabase() {
        givenShop();
        givenGoods(goods(7L, SHOP_ID, "88.00", 10, true));
        when(goodsMapper.deductStock(7L, 2)).thenReturn(1);

        Submitted submitted = captureSubmit(item(7L, 2));

        assertThat(submitted.order().getTotalAmount()).isEqualByComparingTo("176.00");
        assertThat(submitted.order().getDiscountAmount()).isEqualByComparingTo("0.00");
        assertThat(submitted.order().getPayAmount()).isEqualByComparingTo("176.00");
        // 标度也要钉住：同一单一次回 176 一次回 176.00，前端展示就会飘
        assertThat(submitted.order().getPayAmount().scale()).isEqualTo(2);
        assertThat(submitted.line().getUnitPrice()).isEqualByComparingTo("88.00");
        assertThat(submitted.line().getSubtotal()).isEqualByComparingTo("176.00");
        assertThat(submitted.line().getGoodsName()).isEqualTo("套餐A");
        assertThat(submitted.line().getGoodsId()).isEqualTo(7L);
        assertThat(submitted.line().getOrderId()).isEqualTo(ORDER_ID);
    }

    @Test
    @DisplayName("订单号、核销码、有效期在落库前由服务端生成，不依赖数据库默认值")
    void identifiersAndExpiryGenerated() {
        givenShop();
        givenGoods(goods(7L, SHOP_ID, "88.00", 10, true));
        when(goodsMapper.deductStock(7L, 1)).thenReturn(1);

        Order order = captureSubmit(item(7L, 1)).order();
        assertThat(order.getNumber()).matches("^L\\d{18}$");
        assertThat(order.getVerifyCode()).matches("^\\d{10}$");
        assertThat(order.getStatus()).isEqualTo(Order.STATUS_PENDING_PAYMENT);
        assertThat(order.getPayStatus()).isEqualTo(Order.PAY_UNPAID);
        assertThat(order.getExpireTime()).isAfter(LocalDateTime.now().plusDays(29));
    }

    @Test
    @DisplayName("券面额大于订单金额时只抵到清零，不会出现负实付")
    void discountCappedAtOrderAmount() {
        givenShop();
        givenGoods(goods(7L, SHOP_ID, "88.00", 10, true));
        when(goodsMapper.deductStock(7L, 1)).thenReturn(1);
        givenCoupon(coupon(9L, SHOP_ID, "300.00", "0.00", UserCoupon.STATUS_UNUSED, null));
        when(userCouponMapper.markUsed(9L, USER_ID, ORDER_ID)).thenReturn(1);

        Order order = captureSubmit(9L, item(7L, 1)).order();
        assertThat(order.getDiscountAmount()).isEqualByComparingTo("88.00");
        assertThat(order.getPayAmount()).isEqualByComparingTo("0.00");
        assertThat(order.getUserCouponId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("没到门槛、别人店的、已用过、已过期的券都在写库前被挡掉")
    void couponRules() {
        givenShop();
        givenGoods(goods(7L, SHOP_ID, "88.00", 10, true));
        when(goodsMapper.deductStock(7L, 1)).thenReturn(1);

        assertFailsWithCoupon(coupon(9L, SHOP_ID, "50.00", "100.00", UserCoupon.STATUS_UNUSED, null),
                40901, "门槛");
        assertFailsWithCoupon(coupon(9L, 2L, "50.00", "50.00", UserCoupon.STATUS_UNUSED, null),
                40901, "不属于该商家");
        assertFailsWithCoupon(coupon(9L, SHOP_ID, "50.00", "50.00", UserCoupon.STATUS_USED, null),
                40901, "已经用过");
        assertFailsWithCoupon(coupon(9L, SHOP_ID, "50.00", "50.00", UserCoupon.STATUS_UNUSED,
                LocalDateTime.now().minusDays(1)), 40901, "已过期");
        // 77 这张券从没被 stub 过：getByIdAndUser 查不到行，就是"这张券不是你的"
        assertFails(() -> service.submit(couponForm(item(7L, 1), 77L), USER_ID), 40401, "优惠券不存在");

        verify(orderMapper, never()).insert(any());
    }

    @Test
    @DisplayName("并发下券被另一单抢先核销：整单抛错，让扣掉的库存随事务一起回滚")
    void couponAlreadyTakenRollsBack() {
        givenShop();
        givenGoods(goods(7L, SHOP_ID, "88.00", 10, true));
        when(goodsMapper.deductStock(7L, 1)).thenReturn(1);
        givenCoupon(coupon(9L, SHOP_ID, "50.00", "50.00", UserCoupon.STATUS_UNUSED, null));
        when(userCouponMapper.markUsed(9L, USER_ID, ORDER_ID)).thenReturn(0);

        assertThatThrownBy(() -> captureSubmit(9L, item(7L, 1)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("这张券刚刚已被使用");
    }

    @Test
    @DisplayName("套餐不存在、已下架、不属于该商家：拦在扣库存之前")
    void goodsRejections() {
        givenShop();

        // 库里查不到这个 id：批量读回来就是少这一行（改之前这里是 getById 返回 null）
        when(goodsMapper.listByIds(List.of(404L))).thenReturn(List.of());
        assertFails(() -> service.submit(form(item(404L, 1)), USER_ID), 40401, "套餐不存在");

        givenGoods(goods(7L, SHOP_ID, "88.00", 10, false));
        assertFails(() -> service.submit(form(item(7L, 1)), USER_ID), 40901, "已下架");
        verify(goodsMapper, never()).deductStock(any(), anyInt());

        givenGoods(goods(8L, 99L, "88.00", 10, true));
        assertFails(() -> service.submit(form(item(8L, 1)), USER_ID), 40001, "不属于该商家");
        verify(goodsMapper, never()).deductStock(any(), anyInt());
    }

    @Test
    @DisplayName("库存先看 goods.stock 给准确文案：售罄和只剩两份是两句话")
    void stockMessagesDistinguishSoldOutFromShortage() {
        givenShop();
        givenGoods(goods(7L, SHOP_ID, "88.00", 0, true));
        assertFails(() -> service.submit(form(item(7L, 1)), USER_ID), 40901, "已售罄");

        givenGoods(goods(7L, SHOP_ID, "88.00", 2, true));
        assertFails(() -> service.submit(form(item(7L, 5)), USER_ID), 40901, "仅剩 2 份");
    }

    @Test
    @DisplayName("守卫式 UPDATE 返回 0 时，连「抢完」这种瞬时情况也不写库")
    void deductStockGuardIsTheRealGate() {
        givenShop();
        // stock 列读出来还有 3 份，但另一事务在同一瞬间扣走了——预检只是文案，防超卖靠条件 UPDATE
        givenGoods(goods(7L, SHOP_ID, "88.00", 3, true));
        when(goodsMapper.deductStock(7L, 3)).thenReturn(0);

        assertFails(() -> service.submit(form(item(7L, 3)), USER_ID), 40901, "刚刚被抢完");
        verify(orderMapper, never()).insert(any());
    }

    @Test
    @DisplayName("支付只认守卫式 UPDATE 的影响行数：0 行就是状态不对")
    void payReliesOnGuard() {
        givenOrder(order(Order.STATUS_PENDING_PAYMENT, null));
        when(orderMapper.updateStatusWithGuard(any(), any(), anyList())).thenReturn(0);

        assertFails(() -> service.pay(ORDER_ID, USER_ID), 40901, "无法支付");
    }

    @Test
    @DisplayName("支付成功时守卫条件带的是「待支付」，改的是待使用+已支付+MOCK 渠道")
    void payWritesTheRightPatch() {
        givenOrder(order(Order.STATUS_PENDING_PAYMENT, null));
        givenDetailRead();
        when(orderMapper.updateStatusWithGuard(any(), any(), anyList())).thenReturn(1);
        ArgumentCaptor<Order> patch = ArgumentCaptor.captor();
        ArgumentCaptor<List<Integer>> expected = ArgumentCaptor.captor();

        service.pay(ORDER_ID, USER_ID);

        verify(orderMapper).updateStatusWithGuard(patch.capture(), org.mockito.ArgumentMatchers.eq(USER_ID),
                expected.capture());
        assertThat(patch.getValue().getStatus()).isEqualTo(Order.STATUS_TO_USE);
        assertThat(patch.getValue().getPayStatus()).isEqualTo(Order.PAY_PAID);
        assertThat(patch.getValue().getPayMethod()).isEqualTo("MOCK");
        assertThat(patch.getValue().getPayTime()).isNotNull();
        assertThat(expected.getValue()).containsExactly(Order.STATUS_PENDING_PAYMENT);
    }

    @Test
    @DisplayName("取消已支付的单回退款文案，取消已完成的单回通用文案")
    void cancelDistinguishesPaidFromCompleted() {
        givenOrder(order(Order.STATUS_TO_USE, null));
        when(orderMapper.updateStatusWithGuard(any(), any(), anyList())).thenReturn(0);
        assertFails(() -> service.cancel(ORDER_ID, USER_ID), 40901, "请先申请退款");
        verify(goodsMapper, never()).restoreStockByOrderIds(anyList(), anyInt());

        givenOrder(order(Order.STATUS_COMPLETED, null));
        assertFails(() -> service.cancel(ORDER_ID, USER_ID), 40901, "无法取消");
    }

    @Test
    @DisplayName("只有真取消掉的单才还券还库存；没用券的单不碰券表")
    void cancelReleasesCouponAndStock() {
        givenOrder(order(Order.STATUS_PENDING_PAYMENT, 9L));
        givenDetailRead();
        when(orderMapper.updateStatusWithGuard(any(), any(), anyList())).thenReturn(1);

        service.cancel(ORDER_ID, USER_ID);

        verify(userCouponMapper).releaseByOrderIds(List.of(ORDER_ID), Order.STATUS_CANCELLED);
        verify(goodsMapper).restoreStockByOrderIds(List.of(ORDER_ID), Order.STATUS_CANCELLED);
    }

    @Test
    @DisplayName("没用券的单取消后，releaseByOrderIds 一次都不必调用")
    void cancelWithoutCouponSkipsRelease() {
        givenOrder(order(Order.STATUS_PENDING_PAYMENT, null));
        givenDetailRead();
        when(orderMapper.updateStatusWithGuard(any(), any(), anyList())).thenReturn(1);

        service.cancel(ORDER_ID, USER_ID);

        verify(userCouponMapper, never()).releaseByOrderIds(anyList(), anyInt());
        verify(goodsMapper).restoreStockByOrderIds(List.of(ORDER_ID), Order.STATUS_CANCELLED);
    }

    @Test
    @DisplayName("核销码过了有效期就不能核销，哪怕订单还停在待使用")
    void completeRejectsExpiredVerifyCode() {
        Order expired = order(Order.STATUS_TO_USE, null);
        expired.setExpireTime(LocalDateTime.now().minusDays(1));
        givenOrder(expired);

        assertFails(() -> service.complete(ORDER_ID, USER_ID), 40901, "已过有效期");
        verify(orderMapper, never()).updateStatusWithGuard(any(), any(), anyList());
    }

    @Test
    @DisplayName("核销只允许从「待使用」过来，待支付的单状态机走不通")
    void completeGuardsOnToUseOnly() {
        Order unpaid = order(Order.STATUS_PENDING_PAYMENT, null);
        unpaid.setExpireTime(LocalDateTime.now().plusDays(1));
        givenOrder(unpaid);
        when(orderMapper.updateStatusWithGuard(any(), any(), anyList())).thenReturn(0);

        assertFails(() -> service.complete(ORDER_ID, USER_ID), 40901, "待使用的订单");
    }

    @Test
    @DisplayName("超时批量关闭：没有到期单就一次写都不做")
    void timeoutSkipsWhenNothingDue() {
        when(orderMapper.listTimeoutIds(any(), anyInt())).thenReturn(List.of());

        assertThat(service.cancelTimeoutOrders(5)).isZero();
        verify(orderMapper, never()).cancelByIds(anyList(), any(), anyInt(), anyInt());
        verify(userCouponMapper, never()).releaseByOrderIds(anyList(), anyInt());
    }

    @Test
    @DisplayName("超时关闭成功的批次，券和库存按同一批 id 还回去")
    void timeoutReleasesCouponAndStock() {
        when(orderMapper.listTimeoutIds(any(), anyInt())).thenReturn(List.of(11L, 12L));
        when(orderMapper.cancelByIds(anyList(), any(), anyInt(), anyInt())).thenReturn(2);

        assertThat(service.cancelTimeoutOrders(5)).isEqualTo(2);
        verify(orderMapper).cancelByIds(List.of(11L, 12L), Order.TIMEOUT_CANCEL_REASON,
                Order.STATUS_PENDING_PAYMENT, Order.STATUS_CANCELLED);
        verify(userCouponMapper).releaseByOrderIds(List.of(11L, 12L), Order.STATUS_CANCELLED);
        verify(goodsMapper).restoreStockByOrderIds(List.of(11L, 12L), Order.STATUS_CANCELLED);
    }

    @Test
    @DisplayName("批量关闭影响 0 行（那一瞬间用户全付了）就不该再去还券，否则会把在用的券放回去")
    void timeoutSkipsReleaseWhenNothingCancelled() {
        when(orderMapper.listTimeoutIds(any(), anyInt())).thenReturn(List.of(11L));
        when(orderMapper.cancelByIds(anyList(), any(), anyInt(), anyInt())).thenReturn(0);

        assertThat(service.cancelTimeoutOrders(5)).isZero();
        verify(userCouponMapper, never()).releaseByOrderIds(anyList(), anyInt());
        verify(goodsMapper, never()).restoreStockByOrderIds(anyList(), anyInt());
    }

    @Test
    @DisplayName("超时阈值换算成 deadline 传给 SQL，而不是把全表捞回来在内存里筛")
    void timeoutPassesDeadline() {
        when(orderMapper.listTimeoutIds(any(), anyInt())).thenReturn(List.of());
        ArgumentCaptor<LocalDateTime> deadline = ArgumentCaptor.captor();

        service.cancelTimeoutOrders(5);

        verify(orderMapper).listTimeoutIds(deadline.capture(), org.mockito.ArgumentMatchers.eq(
                Order.STATUS_PENDING_PAYMENT));
        assertThat(deadline.getValue()).isBefore(LocalDateTime.now().minusMinutes(4));
    }

    @Test
    @DisplayName("列表页的 tab 词翻成库里的数字；乱传直接 40001，不静默当成「全部」")
    void statusWordTranslated() {
        assertFails(() -> service.page(1, 10, "BOGUS", USER_ID), 40001, "status 只支持");
    }

    @Test
    @DisplayName("带 requestId 的首次提交：SETNX 占位成功，成单后把键换成订单 id")
    void firstSubmitClaimsIdempotencyKey() {
        givenShop();
        givenGoods(goods(7L, SHOP_ID, "88.00", 10, true));
        when(goodsMapper.deductStock(7L, 1)).thenReturn(1);
        when(redis.opsForValue()).thenReturn(values);
        String key = RedisKeyConstant.orderRequest(USER_ID, "req-1");
        when(values.setIfAbsent(eq(key), eq(RedisKeyConstant.ORDER_REQUEST_PENDING), any(Duration.class)))
                .thenReturn(true);
        when(orderMapper.insert(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, Order.class).setId(ORDER_ID);
            return 1;
        });

        OrderVO vo = service.submit(requestIdForm("req-1"), USER_ID);

        assertThat(vo.id()).isEqualTo(ORDER_ID);
        verify(values).set(key, String.valueOf(ORDER_ID),
                Duration.ofSeconds(RedisKeyConstant.ORDER_REQUEST_TTL_SECONDS));
        verify(redis, never()).delete(anyString());
    }

    @Test
    @DisplayName("重复提交且上一次还在进行：40901 让用户稍等，绝不第二次扣库存")
    void duplicateWhileFirstIsInFlight() {
        when(redis.opsForValue()).thenReturn(values);
        String key = RedisKeyConstant.orderRequest(USER_ID, "req-2");
        when(values.setIfAbsent(eq(key), anyString(), any(Duration.class))).thenReturn(false);
        when(values.get(key)).thenReturn(RedisKeyConstant.ORDER_REQUEST_PENDING);

        assertFails(() -> service.submit(requestIdForm("req-2"), USER_ID), 40901, "正在提交中");
        verify(goodsMapper, never()).deductStock(any(), anyInt());
        verify(orderMapper, never()).insert(any());
    }

    @Test
    @DisplayName("重复提交且上一次已成单：回同一张单，不再写库也不再扣库存")
    void duplicateReturnsTheSameOrder() {
        when(redis.opsForValue()).thenReturn(values);
        String key = RedisKeyConstant.orderRequest(USER_ID, "req-3");
        when(values.setIfAbsent(eq(key), anyString(), any(Duration.class))).thenReturn(false);
        when(values.get(key)).thenReturn(String.valueOf(ORDER_ID));
        Order existing = order(Order.STATUS_PENDING_PAYMENT, null);
        existing.setNumber("L202610250000010001");
        existing.setVerifyCode("0000007777");
        givenOrder(existing);
        givenDetailRead();

        OrderVO vo = service.submit(requestIdForm("req-3"), USER_ID);

        assertThat(vo.id()).isEqualTo(ORDER_ID);
        // 回的是同一张单，不是新造一张：订单号和核销码都得原样
        assertThat(vo.number()).isEqualTo("L202610250000010001");
        assertThat(vo.verifyCode()).isEqualTo("0000007777");
        verify(goodsMapper, never()).deductStock(any(), anyInt());
        verify(orderMapper, never()).insert(any());
    }

    @Test
    @DisplayName("幂等重放也只认本人：别人的单查不到就当成没成过，不泄露存在性")
    void replayCannotReadOtherUsersOrder() {
        when(redis.opsForValue()).thenReturn(values);
        String key = RedisKeyConstant.orderRequest(USER_ID, "req-4");
        when(values.setIfAbsent(eq(key), anyString(), any(Duration.class))).thenReturn(false);
        when(values.get(key)).thenReturn(String.valueOf(ORDER_ID));
        when(orderMapper.getByIdAndUser(ORDER_ID, USER_ID)).thenReturn(null);

        assertFails(() -> service.submit(requestIdForm("req-4"), USER_ID), 40901, "上一次提交未完成");
        verify(redis).delete(key);
    }

    @Test
    @DisplayName("下单中途失败要把幂等键删掉，否则同一个 requestId 永远重试不了")
    void failureReleasesIdempotencyKey() {
        givenShop();
        givenGoods(goods(7L, SHOP_ID, "88.00", 0, true));   // 售罄，创建阶段就抛
        when(redis.opsForValue()).thenReturn(values);
        String key = RedisKeyConstant.orderRequest(USER_ID, "req-5");
        when(values.setIfAbsent(eq(key), anyString(), any(Duration.class))).thenReturn(true);

        assertFails(() -> service.submit(requestIdForm("req-5"), USER_ID), 40901, "已售罄");
        verify(redis).delete(key);
    }

    @Test
    @DisplayName("不带 requestId 就退回普通下单：一次 Redis 都不碰，兼容现有调用方")
    void withoutRequestIdSkipsTheGate() {
        givenShop();
        givenGoods(goods(7L, SHOP_ID, "88.00", 10, true));
        when(goodsMapper.deductStock(7L, 1)).thenReturn(1);
        when(orderMapper.insert(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, Order.class).setId(ORDER_ID);
            return 1;
        });

        assertThat(service.submit(form(item(7L, 1)), USER_ID).id()).isEqualTo(ORDER_ID);
        verifyNoInteractions(redis);
    }

    /**
     * 三个套餐的下单：改之前是 **3 次 getById + 3 次 deductStock**（基线 2N+1 次往返，
     * 这条测试最早断言的就是那个旧形状，量到 3+3 之后才动手改）。
     * 现在读被并成一次 IN，**扣减仍是一条一句**——那句带 {@code stock >= quantity} 的 UPDATE
     * 是防超卖的地方，把它也折成一条语句就等于把守卫搬回 Java 里重算。
     * 反向对照：把 Service 里那次 listByIds 换回循环 getById，这条立刻红。
     */
    @Test
    @DisplayName("三个套餐只读一次库（批量 IN），但扣减仍然一商品一句：守卫不能折进一条语句")
    void threeItemOrderReadsGoodsOnceButDeductsPerLine() {
        givenShop();
        givenGoods(goods(7L, SHOP_ID, "30.00", 10, true),
                goods(8L, SHOP_ID, "20.00", 10, true),
                goods(9L, SHOP_ID, "10.00", 10, true));
        when(goodsMapper.deductStock(any(), anyInt())).thenReturn(1);
        when(orderMapper.insert(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, Order.class).setId(ORDER_ID);
            return 1;
        });

        service.submit(form(item(7L, 1), item(8L, 2), item(9L, 3)), USER_ID);

        verify(goodsMapper, times(1)).listByIds(anyList());
        verify(goodsMapper, never()).getById(any());
        verify(goodsMapper).deductStock(7L, 1);
        verify(goodsMapper).deductStock(8L, 2);
        verify(goodsMapper).deductStock(9L, 3);
    }

    /** 明细里同一个套餐买两行：读仍然只问一次，扣减按行各扣一次，金额按行累加。 */
    @Test
    @DisplayName("同一套餐出现两行：IN 里 id 去重，扣减按行算")
    void duplicatedGoodsIdReadsOnceAndDeductsPerLine() {
        givenShop();
        givenGoods(goods(7L, SHOP_ID, "30.00", 10, true));
        when(goodsMapper.deductStock(any(), anyInt())).thenReturn(1);

        Submitted submitted = captureSubmit(item(7L, 1), item(7L, 2));

        verify(goodsMapper, times(1)).listByIds(List.of(7L));
        // 两行各扣各的数量：读被合并了，扣减没有——这两句少了任何一句都会把库存算错
        verify(goodsMapper).deductStock(7L, 1);
        verify(goodsMapper).deductStock(7L, 2);
        verify(goodsMapper, times(2)).deductStock(any(), anyInt());
        assertThat(submitted.order().getTotalAmount()).isEqualByComparingTo("90.00");
    }

    // ---- helpers ----

    private static OrderSubmitDTO requestIdForm(String requestId) {
        OrderSubmitDTO form = form(item(7L, 1));
        form.setRequestId(requestId);
        return form;
    }

    private void givenShop() {
        when(shopMapper.getById(SHOP_ID)).thenReturn(new Shop());
    }

    /** 下单那批套餐只被读一次（IN 查询），所以桩也只需要打一次；改成 varargs 是为了多套餐用例。 */
    private void givenGoods(Goods... goods) {
        when(goodsMapper.listByIds(anyList())).thenReturn(List.of(goods));
    }

    private void givenCoupon(UserCoupon coupon) {
        when(userCouponMapper.getByIdAndUser(coupon.getId(), USER_ID)).thenReturn(coupon);
    }


    @Test
    @DisplayName("退款只对「待使用」生效，成功后还券还库存并写退款时间")
    void refundReleasesCouponAndStock() {
        givenOrder(order(Order.STATUS_TO_USE, 9L));
        givenDetailRead();
        when(orderMapper.updateStatusWithGuard(any(), any(), anyList())).thenReturn(1);

        service.refund(ORDER_ID, USER_ID);

        verify(userCouponMapper).releaseByOrderIds(List.of(ORDER_ID), Order.STATUS_REFUNDED);
        verify(goodsMapper).restoreStockByOrderIds(List.of(ORDER_ID), Order.STATUS_REFUNDED);
    }

    @Test
    @DisplayName("退款被守卫挡下时一分东西都不还：待支付、已完成、已退款各有各的说法")
    void refundRefusalsRestoreNothing() {
        when(orderMapper.updateStatusWithGuard(any(), any(), anyList())).thenReturn(0);

        givenOrder(order(Order.STATUS_PENDING_PAYMENT, null));
        assertFails(() -> service.refund(ORDER_ID, USER_ID), 40901, "还没付款");

        givenOrder(order(Order.STATUS_COMPLETED, null));
        assertFails(() -> service.refund(ORDER_ID, USER_ID), 40901, "已经消费过");

        givenOrder(order(Order.STATUS_REFUNDED, null));
        assertFails(() -> service.refund(ORDER_ID, USER_ID), 40901, "已经退过款");

        verify(goodsMapper, never()).restoreStockByOrderIds(anyList(), anyInt());
        verify(userCouponMapper, never()).releaseByOrderIds(anyList(), anyInt());
    }

    @Test
    @DisplayName("没用券的单退款不碰券表")
    void refundWithoutCouponSkipsRelease() {
        givenOrder(order(Order.STATUS_TO_USE, null));
        givenDetailRead();
        when(orderMapper.updateStatusWithGuard(any(), any(), anyList())).thenReturn(1);

        service.refund(ORDER_ID, USER_ID);

        verify(userCouponMapper, never()).releaseByOrderIds(anyList(), anyInt());
        verify(goodsMapper).restoreStockByOrderIds(List.of(ORDER_ID), Order.STATUS_REFUNDED);
    }

    private void givenOrder(Order order) {
        when(orderMapper.getByIdAndUser(ORDER_ID, USER_ID)).thenReturn(order);
    }

    /** 状态改成功之后 Service 会重新拼一次详情；只有改成功的用例需要这条 stub。 */
    private void givenDetailRead() {
        when(orderDetailMapper.listByOrderIds(List.of(ORDER_ID))).thenReturn(List.of());
    }

    private void assertFailsWithCoupon(UserCoupon coupon, int code, String messagePart) {
        givenCoupon(coupon);
        assertFails(() -> service.submit(couponForm(item(7L, 1), coupon.getId()), USER_ID), code, messagePart);
    }

    /**
     * 走完下单并捕获真正写库的主单和明细。insert 用答案回填自增 id，模拟 useGeneratedKeys——
     * 明细的 order_id 和券核销都要用这个 id，不回填的话断言只能靠猜。
     */
    private Submitted captureSubmit(Long userCouponId, OrderItemDTO... items) {
        when(orderMapper.insert(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, Order.class).setId(ORDER_ID);
            return 1;
        });
        service.submit(userCouponId == null ? form(items) : couponForm(items[0], userCouponId), USER_ID);
        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.captor();
        ArgumentCaptor<List<OrderDetail>> linesCaptor = ArgumentCaptor.captor();
        verify(orderMapper).insert(orderCaptor.capture());
        verify(orderDetailMapper).insertBatch(linesCaptor.capture());
        return new Submitted(orderCaptor.getValue(), linesCaptor.getValue().get(0));
    }

    private Submitted captureSubmit(OrderItemDTO... items) {
        return captureSubmit(null, items);
    }

    private static void assertFails(Runnable call, int code, String messagePart) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code))
                .hasMessageContaining(messagePart);
    }

    private static OrderSubmitDTO form(OrderItemDTO... items) {
        OrderSubmitDTO form = new OrderSubmitDTO();
        form.setShopId(SHOP_ID);
        form.setItems(List.of(items));
        return form;
    }

    private static OrderSubmitDTO couponForm(OrderItemDTO item, Long userCouponId) {
        OrderSubmitDTO form = form(item);
        form.setUserCouponId(userCouponId);
        return form;
    }

    private static OrderItemDTO item(long goodsId, int quantity) {
        OrderItemDTO item = new OrderItemDTO();
        item.setGoodsId(goodsId);
        item.setQuantity(quantity);
        return item;
    }

    private static Goods goods(long id, long shopId, String price, int stock, boolean onSale) {
        Goods goods = new Goods();
        goods.setId(id);
        goods.setShopId(shopId);
        goods.setName("套餐A");
        goods.setPrice(new BigDecimal(price));
        goods.setStock(stock);
        goods.setStatus(onSale ? 1 : 0);
        return goods;
    }

    private static UserCoupon coupon(long id, long shopId, String amount, String threshold,
                                     int status, LocalDateTime expireTime) {
        UserCoupon coupon = new UserCoupon();
        coupon.setId(id);
        coupon.setShopId(shopId);
        coupon.setUserId(USER_ID);
        coupon.setName("满" + threshold + "减" + amount);
        coupon.setAmount(new BigDecimal(amount));
        coupon.setThresholdAmount(new BigDecimal(threshold));
        coupon.setStatus(status);
        coupon.setExpireTime(expireTime);
        return coupon;
    }

    private static Order order(int status, Long userCouponId) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setUserId(USER_ID);
        order.setShopId(SHOP_ID);
        order.setStatus(status);
        order.setUserCouponId(userCouponId);
        order.setTotalAmount(new BigDecimal("88.00"));
        order.setPayAmount(new BigDecimal("88.00"));
        order.setExpireTime(LocalDateTime.now().plusDays(10));
        return order;
    }

    /** 捕获到的"主单 + 第一条明细"，省掉每个断言再写一遍 captor.getValue()。 */
    private record Submitted(Order order, OrderDetail line) {
    }
}
