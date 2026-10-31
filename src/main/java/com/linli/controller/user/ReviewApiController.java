package com.linli.controller.user;

import com.linli.annotation.RequiresLogin;
import com.linli.common.PageResult;
import com.linli.common.Result;
import com.linli.context.UserContext;
import com.linli.dto.ReviewForm;
import com.linli.service.ReviewService;
import com.linli.vo.ReviewVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家评价接口。列表匿名可看（详情页要展示），提交要登录。
 *
 * 路径挂在商家下（{@code /api/shops/{shopId}/reviews}）而不是 {@code /api/reviews}：
 * 读这条路径的人一眼就知道在看谁的评分，也不用再传一次 shopId——它和订单归属的一致性由 Service 校验。
 */
@RestController
@RequestMapping("/api/shops/{shopId}/reviews")
public class ReviewApiController {

    private final ReviewService reviewService;

    public ReviewApiController(ReviewService reviewService) { this.reviewService = reviewService; }

    @GetMapping
    public Result<PageResult<ReviewVO>> page(@PathVariable("shopId") Long shopId,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "10") int pageSize) {
        return Result.ok(reviewService.pageByShop(shopId, page, pageSize));
    }

    @PostMapping
    @RequiresLogin
    public Result<ReviewVO> create(@PathVariable("shopId") Long shopId, @Valid @RequestBody ReviewForm form) {
        return Result.ok(reviewService.create(shopId, form, UserContext.requireUserId()));
    }
}
