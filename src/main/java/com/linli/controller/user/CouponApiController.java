package com.linli.controller.user;

import com.linli.annotation.RequiresLogin;
import com.linli.common.PageResult;
import com.linli.common.Result;
import com.linli.context.LoginUser;
import com.linli.context.UserContext;
import com.linli.dto.CouponQueryDTO;
import com.linli.service.CouponService;
import com.linli.vo.CouponVO;
import com.linli.vo.MyCouponVO;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 目标优惠券接口（API_CONTRACT.md 第 5 节）。列表类接口匿名可看，但会**顺带**按当前登录用户算
 * received 标记，所以未登录也能用；我的券包和领取用 {@link RequiresLogin} 声明必须登录，
 * 不再往 WebMvcConfiguration 的路径清单里加行。
 */
@RestController
@RequestMapping("/api")
public class CouponApiController {

    private final CouponService couponService;

    public CouponApiController(CouponService couponService) { this.couponService = couponService; }

    /** 券中心分页。shopId 可选，只看某家商家的券。 */
    @GetMapping("/coupons")
    public Result<PageResult<CouponVO>> coupons(@RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "10") int pageSize,
                                                @RequestParam(required = false) Long shopId) {
        CouponQueryDTO query = new CouponQueryDTO();
        query.setPage(page);
        query.setPageSize(pageSize);
        query.setShopId(shopId);
        query.setUserId(currentUserIdOrNull());
        return Result.ok(couponService.page(query));
    }

    /** 商家详情页的可用券，最多 20 张。 */
    @GetMapping("/shops/{id}/coupons")
    public Result<List<CouponVO>> shopCoupons(@PathVariable("id") Long id) {
        return Result.ok(couponService.listByShop(id, currentUserIdOrNull()));
    }

    @GetMapping("/coupons/my")
    @RequiresLogin
    public Result<List<MyCouponVO>> my(@RequestParam(required = false) String status) {
        return Result.ok(couponService.my(UserContext.requireUserId(), status));
    }

    /** 领取。同一用户重复调用只会有一张，返回 40901「你已经领取过这张券」。 */
    @PostMapping("/coupons/{id}/claim")
    @RequiresLogin
    public Result<MyCouponVO> claim(@PathVariable("id") Long id) {
        return Result.ok(couponService.claim(id, UserContext.requireUserId()));
    }

    private static Long currentUserIdOrNull() {
        LoginUser current = UserContext.current();
        return current == null ? null : current.userId();
    }
}
