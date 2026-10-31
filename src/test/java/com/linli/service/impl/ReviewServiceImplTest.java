package com.linli.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linli.common.ApiException;
import com.linli.common.ErrorCode;
import com.linli.common.OwnImageGuard;
import com.linli.common.PageResult;
import com.linli.dto.ReviewForm;
import com.linli.entity.Order;
import com.linli.entity.Review;
import com.linli.mapper.OrderMapper;
import com.linli.mapper.ReviewMapper;
import com.linli.service.ReviewService;
import com.linli.service.ShopService;
import com.linli.vo.ReviewVO;
import com.linli.vo.MineVOs;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * 商家评价的单元测试。守的是"评价资格"这条不能被绕过的规矩：
 * 只能评自己名下、属于该商家、已完成的订单，且一单一条。
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final Long SHOP_ID = 1L;
    private static final Long ORDER_ID = 9002L;

    @Mock private ReviewMapper reviewMapper;
    @Mock private OrderMapper orderMapper;
    @Mock private ShopService shopService;
    @Mock private OwnImageGuard imageGuard;

    private ReviewServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ReviewServiceImpl(reviewMapper, orderMapper, shopService, imageGuard);
    }

    // ---------- 资格判定 ----------

    @Test
    @DisplayName("已完成的订单能评：写进去的作者是会话用户，不是请求里传的人")
    void completedOrderIsReviewable() {
        givenOrder(Order.STATUS_COMPLETED);
        givenInsertedReviewEchoedBack();

        ReviewVO result = service.create(SHOP_ID, form(5, "  排骨还是那个味  ", List.of()), USER_ID);

        ArgumentCaptor<Review> inserted = ArgumentCaptor.captor();
        verify(reviewMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(inserted.getValue().getOrderId()).isEqualTo(ORDER_ID);
        assertThat(inserted.getValue().getContent()).isEqualTo("排骨还是那个味");
        assertThat(result.author().nickname()).isEqualTo("邻里用户");
    }

    @Test
    @DisplayName("订单没完成就不能评：待支付、待使用、已取消三种都拒")
    void onlyCompletedOrdersCanReview() {
        for (int status : new int[] {Order.STATUS_PENDING_PAYMENT, Order.STATUS_TO_USE, Order.STATUS_CANCELLED}) {
            givenOrder(status);
            assertFails(() -> service.create(SHOP_ID, form(4, "不错", List.of()), USER_ID), 40901, "订单完成后才能评价");
        }
        verify(reviewMapper, never()).insert(any());
    }

    @Test
    @DisplayName("别人的订单查不到就是不存在，不回“这单不是你的”这种能枚举信息的话")
    void otherUsersOrderLooksMissing() {
        when(orderMapper.getByIdAndUser(ORDER_ID, USER_ID)).thenReturn(null);

        assertFails(() -> service.create(SHOP_ID, form(4, "不错", List.of()), USER_ID), 40401, "订单不存在");
    }

    @Test
    @DisplayName("订单不属于路径里那个商家就拒：防止把 A 店的单评到 B 店头上刷分")
    void orderMustBelongToTheShop() {
        Order order = order(Order.STATUS_COMPLETED);
        order.setShopId(999L);
        when(orderMapper.getByIdAndUser(ORDER_ID, USER_ID)).thenReturn(order);

        assertFails(() -> service.create(SHOP_ID, form(4, "不错", List.of()), USER_ID), 40001, "这张订单不属于该商家");
    }

    @Test
    @DisplayName("一单一条：先查一次拦快速路径，唯一索引冲突也翻译成同一句话")
    void duplicateReviewIsConflict() {
        givenOrder(Order.STATUS_COMPLETED);
        when(reviewMapper.countByOrder(ORDER_ID)).thenReturn(1);

        assertFails(() -> service.create(SHOP_ID, form(4, "不错", List.of()), USER_ID), 40901, "这单已经评价过了");
        verify(reviewMapper, never()).insert(any());
    }

    @Test
    @DisplayName("并发下两个请求同时通过 countByOrder：唯一索引兜底，第二个回 40901 而不是 500")
    void uniqueIndexRaceBecomesConflict() {
        givenOrder(Order.STATUS_COMPLETED);
        when(reviewMapper.countByOrder(ORDER_ID)).thenReturn(0);
        when(reviewMapper.insert(any())).thenThrow(new DuplicateKeyException("uk_review_order"));

        assertFails(() -> service.create(SHOP_ID, form(4, "不错", List.of()), USER_ID), 40901, "这单已经评价过了");
        verify(shopService, never()).refreshScore(anyLong(), any());
    }
    // ---------- 入参 ----------

    @Test
    @DisplayName("评分越界、文案空白、缺订单、缺商家 id 都在写库前被拒")
    void invalidInputRejectedBeforeWrite() {
        assertFails(() -> service.create(null, form(5, "不错", List.of()), USER_ID), 40001, "缺少商家 id");
        assertFails(() -> service.create(SHOP_ID, new ReviewForm(null, 5, "不错", null), USER_ID), 40001, "缺少要评价的订单");
        assertFails(() -> service.create(SHOP_ID, form(0, "不错", List.of()), USER_ID), 40001, "评分要在 1 到 5 星之间");
        assertFails(() -> service.create(SHOP_ID, form(6, "不错", List.of()), USER_ID), 40001, "评分要在 1 到 5 星之间");
        assertFails(() -> service.create(SHOP_ID, form(5, "   ", List.of()), USER_ID), 40001, "请写点评价内容");
        verify(reviewMapper, never()).insert(any());
        verifyNoShopLookup();
    }

    @Test
    @DisplayName("商家不存在时先 40401，不去查订单")
    void unknownShopRejectedFirst() {
        when(shopService.detail(SHOP_ID)).thenThrow(new ApiException(ErrorCode.NOT_FOUND, "商家不存在"));

        assertFails(() -> service.create(SHOP_ID, form(5, "不错", List.of()), USER_ID), 40401, "商家不存在");
        verify(orderMapper, never()).getByIdAndUser(anyLong(), anyLong());
    }

    @Test
    @DisplayName("图片只认本平台上传地址：外链在写库前被 OwnImageGuard 拦下")
    void externalImageRejected() {
        givenOrder(Order.STATUS_COMPLETED);
        doThrow(new ApiException(ErrorCode.PARAM_INVALID, "图片只能使用本平台上传的图片地址"))
                .when(imageGuard).requireOwnAll(anyList(), anyString());

        assertFails(() -> service.create(SHOP_ID,
                form(5, "不错", List.of("https://evil.example.com/a.png")), USER_ID), 40001, "图片只能使用本平台");
        verify(reviewMapper, never()).insert(any());
    }

    @Test
    @DisplayName("多张图去重去空白后按逗号存进 images 列")
    void imagesAreDedupedAndJoined() {
        givenOrder(Order.STATUS_COMPLETED);
        givenInsertedReviewEchoedBack();

        service.create(SHOP_ID, form(4, "不错", List.of("/api/file/images/a.png", "  ", "/api/file/images/a.png")),
                USER_ID);

        ArgumentCaptor<Review> inserted = ArgumentCaptor.captor();
        verify(reviewMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getImages()).isEqualTo("/api/file/images/a.png");
    }

    // ---------- 派生值 ----------

    @Test
    @DisplayName("评价之后重算商家展示分，并且走 ShopService（缓存失效在那一层）")
    void shopScoreIsRecalculated() {
        givenOrder(Order.STATUS_COMPLETED);
        givenInsertedReviewEchoedBack();
        when(reviewMapper.averageScore(SHOP_ID)).thenReturn(new BigDecimal("4.5"));

        service.create(SHOP_ID, form(5, "不错", List.of()), USER_ID);

        verify(shopService).refreshScore(SHOP_ID, new BigDecimal("4.5"));
    }

    @Test
    @DisplayName("没有评价时不去动 shop.score：种子分不该被一次空聚合清掉")
    void nullAverageLeavesScoreAlone() {
        givenOrder(Order.STATUS_COMPLETED);
        givenInsertedReviewEchoedBack();
        when(reviewMapper.averageScore(SHOP_ID)).thenReturn(null);

        service.create(SHOP_ID, form(5, "不错", List.of()), USER_ID);

        verify(shopService, never()).refreshScore(anyLong(), any());
    }

    // ---------- 列表 ----------

    @Test
    @DisplayName("评价列表：total 为 0 就不查列表，分页参数被收敛进合法区间")
    void emptyReviewListSkipsQuery() {
        when(reviewMapper.countByShop(SHOP_ID)).thenReturn(0L);

        PageResult<ReviewVO> page = service.pageByShop(SHOP_ID, 0, 999);

        assertThat(page.total()).isZero();
        assertThat(page.list()).isEmpty();
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.pageSize()).isEqualTo(50);
        verify(reviewMapper, never()).pageByShop(anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("评价列表带出评价人，图片按逗号拆成数组")
    void reviewListJoinsAuthorAndSplitsImages() {
        when(reviewMapper.countByShop(SHOP_ID)).thenReturn(1L);
        when(reviewMapper.pageByShop(SHOP_ID, 0, 10)).thenReturn(List.of(storedReview()));

        PageResult<ReviewVO> page = service.pageByShop(SHOP_ID, 1, 10);

        assertThat(page.list()).hasSize(1);
        assertThat(page.list().get(0).author().nickname()).isEqualTo("阿岚");
        assertThat(page.list().get(0).images()).containsExactly("/api/file/images/a.png", "/api/file/images/b.png");
    }

    // ---------- helpers ----------

    private void givenOrder(int status) {
        when(orderMapper.getByIdAndUser(ORDER_ID, USER_ID)).thenReturn(order(status));
        when(shopService.detail(SHOP_ID)).thenReturn(null);
    }

    /** insert 回填 id + getById 回读，模拟"写完再按 join 读一次"那条真实路径。 */
    private void givenInsertedReviewEchoedBack() {
        when(reviewMapper.countByOrder(ORDER_ID)).thenReturn(0);
        when(reviewMapper.insert(any())).thenAnswer(call -> {
            call.getArgument(0, Review.class).setId(700L);
            return 1;
        });
        Review echoed = new Review();
        echoed.setId(700L);
        echoed.setUserId(USER_ID);
        echoed.setShopId(SHOP_ID);
        echoed.setOrderId(ORDER_ID);
        echoed.setContent("排骨还是那个味");
        echoed.setScore(5);
        echoed.setAuthorName("邻里用户");
        echoed.setAuthorIcon("邻");
        echoed.setCreateTime(LocalDateTime.now());
        when(reviewMapper.getById(700L)).thenReturn(echoed);
    }

    private void verifyNoShopLookup() {
        verify(shopService, never()).detail(any());
    }

    private static Order order(int status) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setUserId(USER_ID);
        order.setShopId(SHOP_ID);
        order.setStatus(status);
        return order;
    }

    private static Review storedReview() {
        Review review = new Review();
        review.setId(701L);
        review.setUserId(2L);
        review.setShopId(SHOP_ID);
        review.setOrderId(9001L);
        review.setContent("环境不错");
        review.setScore(4);
        review.setImages("/api/file/images/a.png,,/api/file/images/b.png");
        review.setAuthorName("阿岚");
        review.setAuthorIcon("🌿");
        review.setCreateTime(LocalDateTime.now());
        return review;
    }

    private static ReviewForm form(Integer score, String content, List<String> images) {
        return new ReviewForm(ORDER_ID, score, content, images);
    }

    private static void assertFails(Runnable call, int code, String messagePart) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code))
                .hasMessageContaining(messagePart);
    }

    private static Review mine(int status) {
        Review review = new Review();
        review.setId(90L);
        review.setUserId(7L);
        review.setShopId(2L);
        review.setOrderId(500L);
        review.setContent("醋鱼不腥");
        review.setScore(5);
        review.setStatus(status);
        review.setShopName("江南小馆");
        review.setCreateTime(LocalDateTime.of(2026, 10, 26, 12, 0));
        return review;
    }

    @Test
    @DisplayName("我写的评价：带商家名，被驳回的那条本人也看得见")
    void myReviewsCarryShopName() {
        when(reviewMapper.countByUser(7L)).thenReturn(1L);
        when(reviewMapper.pageByUser(7L, 0, 10)).thenReturn(List.of(mine(2)));

        PageResult<MineVOs.ReviewRow> page = service.pageMine(7L, 1, 10);

        assertThat(page.list().get(0).shopName()).isEqualTo("江南小馆");
        assertThat(page.list().get(0).orderId()).isEqualTo(500L);
    }

    @Test
    @DisplayName("删自己的评价：删行 + 用剩下的评价重算展示分")
    void deleteMineRecalculatesScore() {
        when(reviewMapper.getById(90L)).thenReturn(mine(1));
        when(reviewMapper.averageScore(2L)).thenReturn(new BigDecimal("4.2"));

        service.deleteMine(7L, 90L);

        verify(reviewMapper).deleteByIdAndUser(90L, 7L);
        verify(shopService).refreshScore(2L, new BigDecimal("4.2"));
    }

    @Test
    @DisplayName("删掉的是这家店最后一条已通过的评价：保持上一次的分，不清零")
    void deleteLastApprovedReviewKeepsScore() {
        when(reviewMapper.getById(90L)).thenReturn(mine(1));
        when(reviewMapper.averageScore(2L)).thenReturn(null);

        service.deleteMine(7L, 90L);

        verify(reviewMapper).deleteByIdAndUser(90L, 7L);
        verify(shopService, never()).refreshScore(anyLong(), any());
    }

    @Test
    @DisplayName("删别人的评价和删不存在的评价回同一句话，且一行都不删")
    void deleteOthersReviewIsIndistinguishable() {
        Review other = mine(1);
        other.setUserId(8L);
        when(reviewMapper.getById(90L)).thenReturn(other);

        assertThatThrownBy(() -> service.deleteMine(7L, 90L))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND))
                .hasMessageContaining("评价不存在");

        verify(reviewMapper, never()).deleteByIdAndUser(any(), any());
    }

}
