package com.linli.service.impl;

import com.linli.common.ApiException;
import com.linli.common.ErrorCode;
import com.linli.common.PageResult;
import com.linli.context.UserContext;
import com.linli.dto.AdminReviewQueryDTO;
import com.linli.entity.Review;
import com.linli.mapper.ReviewMapper;
import com.linli.service.AdminReviewService;
import com.linli.service.ShopService;
import com.linli.vo.AdminVOs;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评价审核的实现。三件事互相牵着，所以放在同一个事务里：
 *
 * 1. **驳回不是删行**。{@code uk_review_order} 决定了"一单一条"，删掉就等于允许他再评一次，
 *    而"这条评价违规"和"他没评过"是两件不同的事，库里必须留得下前者。
 * 2. **驳回要连带重算展示分**。{@code reviewMapper.averageScore} 已经只算 {@code status = 1}，
 *    所以驳回后拿它回一次就是新分数；不重算的话，商家详情页少了一条差评，分数却还是带着它的旧值。
 * 3. **重算完必须失效商家详情缓存**。{@code ShopService.refreshScore} 自己就带了删缓存，
 *    这一步不是我想当然加的：详情缓存 30 分钟，不删的话"驳回生效"要等半天。
 *
 * 边界：如果这家商家的评价被全部驳回，{@code averageScore} 返回 null（没有可算的行了）。
 * 这时**保持上一次算出来的分**，不去清零——{@code shop.score} 是展示分，种子数据里那个值
 * 不代表"用户给过的好评"，把它清掉反而是另一种失真。等下一条通过的评价进来，它会自己刷新。
 */
@Service
public class AdminReviewServiceImpl implements AdminReviewService {

    private static final Logger log = LoggerFactory.getLogger(AdminReviewServiceImpl.class);

    private static final Set<Integer> STATUSES = Set.of(1, 2);

    private final ReviewMapper reviewMapper;
    private final ShopService shopService;

    public AdminReviewServiceImpl(ReviewMapper reviewMapper, ShopService shopService) {
        this.reviewMapper = reviewMapper;
        this.shopService = shopService;
    }

    @Override
    public PageResult<AdminVOs.ReviewRow> page(AdminReviewQueryDTO query) {
        AdminReviewQueryDTO condition = normalize(query);
        long total = reviewMapper.countAdmin(condition);
        List<AdminVOs.ReviewRow> list = total == 0 ? List.of()
                : reviewMapper.pageAdmin(condition).stream().map(AdminVOs.ReviewRow::from).toList();
        return new PageResult<>(list, total, condition.normalizedPage(), condition.normalizedPageSize());
    }

    @Override
    @Transactional
    public AdminVOs.StatusResult audit(Long id, Integer status) {
        if (status == null || !STATUSES.contains(status)) {
            throw new ApiException(ErrorCode.PARAM_INVALID, "status 只支持 1（通过）和 2（驳回）");
        }
        Review review = id == null ? null : reviewMapper.getById(id);
        if (review == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "评价不存在");
        }
        reviewMapper.updateStatus(id, status);

        BigDecimal average = reviewMapper.averageScore(review.getShopId());
        if (average != null) {
            shopService.refreshScore(review.getShopId(), average);
        }
        log.debug("评价已审核: reviewId={} shopId={} status={} 展示分={} 操作人={}",
                id, review.getShopId(), status, average, UserContext.userIdOrNull());
        return new AdminVOs.StatusResult(id, status, status == 2 ? "已驳回" : "已通过");
    }

    private AdminReviewQueryDTO normalize(AdminReviewQueryDTO query) {
        AdminReviewQueryDTO condition = query == null ? new AdminReviewQueryDTO() : query;
        condition.setKeyword(condition.trimmedKeyword());
        if (condition.getStatus() != null && !STATUSES.contains(condition.getStatus())) {
            throw new ApiException(ErrorCode.PARAM_INVALID, "status 只支持 1（已通过）或 2（已驳回）");
        }
        return condition;
    }
}
