package com.linli.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linli.common.ApiException;
import com.linli.common.PageResult;
import com.linli.dto.AdminReviewQueryDTO;
import com.linli.entity.Review;
import com.linli.mapper.ReviewMapper;
import com.linli.service.ShopService;
import com.linli.vo.AdminVOs;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 评价审核的单元测试。核心是那条连带关系：**驳回一条评价，必须同时把这家商家的展示分重算一遍**——
 * 只改 review.status 的话，商家详情页少了一条差评，分数却还是带着它的旧值，界面和数字会各说一套。
 */
@ExtendWith(MockitoExtension.class)
class AdminReviewServiceImplTest {

    private static final Long ID = 88L;
    private static final Long SHOP = 2L;

    @Mock private ReviewMapper reviewMapper;
    @Mock private ShopService shopService;

    private AdminReviewServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminReviewServiceImpl(reviewMapper, shopService);
    }

    private static Review review(int status) {
        Review item = new Review();
        item.setId(ID);
        item.setUserId(3L);
        item.setShopId(SHOP);
        item.setOrderId(500L);
        item.setContent("这次有点腥");
        item.setScore(2);
        item.setImages("🐟");
        item.setStatus(status);
        item.setAuthorName("老周");
        item.setShopName("拾光咖啡馆");
        item.setCreateTime(LocalDateTime.of(2026, 10, 26, 11, 0));
        return item;
    }

    @Test
    @DisplayName("列表行带审核状态文字：1 已通过、2 已驳回")
    void mapsStatusText() {
        when(reviewMapper.countAdmin(any())).thenReturn(2L);
        when(reviewMapper.pageAdmin(any())).thenReturn(List.of(review(1), review(2)));

        PageResult<AdminVOs.ReviewRow> page = service.page(new AdminReviewQueryDTO());

        assertThat(page.list()).extracting(AdminVOs.ReviewRow::statusText)
                .containsExactly("已通过", "已驳回");
        assertThat(page.list().get(0).shopName()).isEqualTo("拾光咖啡馆");
        assertThat(page.list().get(0).images()).containsExactly("🐟");
    }

    @Test
    @DisplayName("总数为 0 时不查列表")
    void emptyPageSkipsListQuery() {
        when(reviewMapper.countAdmin(any())).thenReturn(0L);

        assertThat(service.page(new AdminReviewQueryDTO()).list()).isEmpty();
        verify(reviewMapper, never()).pageAdmin(any());
    }

    @Test
    @DisplayName("驳回：改状态 + 用只算已通过评价的均分重算展示分")
    void rejectRecalculatesScore() {
        when(reviewMapper.getById(ID)).thenReturn(review(1));
        when(reviewMapper.averageScore(SHOP)).thenReturn(new BigDecimal("4.6"));

        AdminVOs.StatusResult result = service.audit(ID, 2);

        verify(reviewMapper).updateStatus(ID, 2);
        verify(shopService).refreshScore(SHOP, new BigDecimal("4.6"));
        assertThat(result.statusText()).isEqualTo("已驳回");
    }

    @Test
    @DisplayName("恢复通过：同样要重算，否则分数停在驳回后那一版")
    void approveRecalculatesToo() {
        when(reviewMapper.getById(ID)).thenReturn(review(2));
        when(reviewMapper.averageScore(SHOP)).thenReturn(new BigDecimal("4.2"));

        service.audit(ID, 1);

        verify(reviewMapper).updateStatus(ID, 1);
        verify(shopService).refreshScore(SHOP, new BigDecimal("4.2"));
    }

    @Test
    @DisplayName("这家商家的评价被全部驳回时保持上一次的分，不清零")
    void keepsLastScoreWhenNoApprovedReviewLeft() {
        when(reviewMapper.getById(ID)).thenReturn(review(1));
        when(reviewMapper.averageScore(SHOP)).thenReturn(null);

        service.audit(ID, 2);

        verify(reviewMapper).updateStatus(ID, 2);
        verify(shopService, never()).refreshScore(any(), any());
    }

    @Test
    @DisplayName("状态只认 1 和 2：传 0（笔记那套的下架值）会被拒，且一行都不改")
    void rejectsUnknownStatus() {
        assertThatThrownBy(() -> service.audit(ID, 0))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("status");

        verify(reviewMapper, never()).getById(any());
        verify(reviewMapper, never()).updateStatus(any(), any());
    }

    @Test
    @DisplayName("评价不存在回 404，不动任何行")
    void unknownReviewIsNotFound() {
        when(reviewMapper.getById(ID)).thenReturn(null);

        assertThatThrownBy(() -> service.audit(ID, 2))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("评价不存在");

        verify(reviewMapper, never()).updateStatus(any(), any());
    }
}
