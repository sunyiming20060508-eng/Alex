package com.linli.service.impl;

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
import com.linli.vo.MineVOs;
import com.linli.vo.ReviewVO;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.linli.common.Pagination;
import com.linli.common.Text;

/**
 * 商家评价：把"下单 → 支付 → 核销 → 评价"这条闭环收上。
 *
 * 三条规矩：
 *
 * 1. **评价资格现查数据库**，不信客户端。订单必须存在、属于当前用户、属于这个商家、状态是"已完成"，
 *    四道都过才让写。所以 DTO 里带 orderId 不构成越权面——它只是"我要评哪一单"，不是"我以谁的身份评"。
 * 2. **一单一条由 {@code uk_review_order} 兜底**，Service 把唯一索引冲突翻译成 40901「这单已经评价过了」，
 *    连点两次不会变两条，也不会 500。
 * 3. **shop.score 是同一条事务里重算的派生值**，写分和删详情缓存都交给 ShopService 做（缓存归它管）。
 *    没有评价的商家不会被清空——{@code averageScore} 返回 null 时直接跳过。
 */
@Service
public class ReviewServiceImpl implements ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewServiceImpl.class);

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;

    private final ReviewMapper reviewMapper;
    private final OrderMapper orderMapper;
    private final ShopService shopService;
    private final OwnImageGuard imageGuard;

    public ReviewServiceImpl(ReviewMapper reviewMapper, OrderMapper orderMapper, ShopService shopService,
                             OwnImageGuard imageGuard) {
        this.reviewMapper = reviewMapper;
        this.orderMapper = orderMapper;
        this.shopService = shopService;
        this.imageGuard = imageGuard;
    }

    @Override
    @Transactional
    public ReviewVO create(Long shopId, ReviewForm form, Long userId) {
        if (shopId == null) throw new ApiException(ErrorCode.PARAM_INVALID, "缺少商家 id");
        if (form == null || form.orderId() == null) throw new ApiException(ErrorCode.PARAM_INVALID, "缺少要评价的订单");
        if (form.score() == null || form.score() < 1 || form.score() > 5) {
            throw new ApiException(ErrorCode.PARAM_INVALID, "评分要在 1 到 5 星之间");
        }
        String content = form.content() == null ? "" : form.content().trim();
        if (content.isEmpty()) throw new ApiException(ErrorCode.PARAM_INVALID, "请写点评价内容");

        shopService.detail(shopId);                       // 商家不存在直接 40401
        Order order = orderMapper.getByIdAndUser(form.orderId(), userId);
        if (order == null) throw new ApiException(ErrorCode.NOT_FOUND, "订单不存在");
        if (!shopId.equals(order.getShopId())) throw new ApiException(ErrorCode.PARAM_INVALID, "这张订单不属于该商家");
        if (!Integer.valueOf(Order.STATUS_COMPLETED).equals(order.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "订单完成后才能评价");
        }
        if (reviewMapper.countByOrder(order.getId()) > 0) {
            throw new ApiException(ErrorCode.CONFLICT, "这单已经评价过了");
        }
        List<String> images = normalizeImages(form.images());
        imageGuard.requireOwnAll(images, "图片");

        Review review = new Review();
        review.setUserId(userId);
        review.setShopId(shopId);
        review.setOrderId(order.getId());
        review.setContent(content);
        review.setScore(form.score());
        review.setImages(ReviewVO.joinImages(images));
        try {
            reviewMapper.insert(review);
        } catch (DuplicateKeyException e) {
            // 上面那次 countByOrder 是快速路径，这条是唯一索引给的并发保证：两个请求同时进来也只留一条
            log.debug("重复评价: orderId={} userId={}", order.getId(), userId);
            throw new ApiException(ErrorCode.CONFLICT, "这单已经评价过了");
        }

        BigDecimal average = reviewMapper.averageScore(shopId);
        if (average != null) {
            shopService.refreshScore(shopId, average);
        }
        // 回读一次：作者昵称头像是 join 出来的，直接用手上这个实体会少带作者信息（阶段 4 踩过）
        return ReviewVO.from(reviewMapper.getById(review.getId()));
    }

    @Override
    public PageResult<ReviewVO> pageByShop(Long shopId, int page, int pageSize) {
        Pagination query = Pagination.of(page, pageSize, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        int safePage = query.page();
        int safeSize = query.size();
        long total = reviewMapper.countByShop(shopId);
        List<ReviewVO> list = total == 0 ? List.of()
                : reviewMapper.pageByShop(shopId, (safePage - 1) * safeSize, safeSize).stream()
                        .map(ReviewVO::from).toList();
        return new PageResult<>(list, total, safePage, safeSize);
    }

    @Override
    public PageResult<MineVOs.ReviewRow> pageMine(Long userId, int page, int pageSize) {
        Pagination query = Pagination.of(page, pageSize, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        int safePage = query.page();
        int safeSize = query.size();
        long total = reviewMapper.countByUser(userId);
        List<MineVOs.ReviewRow> list = total == 0 ? List.of()
                : reviewMapper.pageByUser(userId, (safePage - 1) * safeSize, safeSize).stream()
                        .map(MineVOs.ReviewRow::from).toList();
        return new PageResult<>(list, total, safePage, safeSize);
    }

    @Override
    @Transactional
    public void deleteMine(Long userId, Long id) {
        Review review = id == null ? null : reviewMapper.getById(id);
        // 不存在和"存在但不是你的"回同一句话：否则这个接口能用来探测别人的评价 id
        if (review == null || !Objects.equals(userId, review.getUserId())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "评价不存在");
        }
        reviewMapper.deleteByIdAndUser(id, userId);
        BigDecimal average = reviewMapper.averageScore(review.getShopId());
        if (average != null) {
            shopService.refreshScore(review.getShopId(), average);
        }
        log.debug("用户删除自己的评价: reviewId={} userId={} shopId={} 重算后展示分={}",
                id, userId, review.getShopId(), average);
    }

    private static List<String> normalizeImages(List<String> images) {
        return Text.distinctNonBlank(images);
    }
}
