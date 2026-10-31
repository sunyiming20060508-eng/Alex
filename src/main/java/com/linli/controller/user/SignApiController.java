package com.linli.controller.user;

import com.linli.annotation.RequiresLogin;
import com.linli.common.PageResult;
import com.linli.common.Result;
import com.linli.context.UserContext;
import com.linli.service.PointsService;
import com.linli.service.SignService;
import com.linli.vo.SignVOs;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 每日签到与积分。
 *
 * 签到是 POST、状态是 GET 分开，是因为状态页每次进个人中心都要读，而签到一天只可能发生一次；
 * 把两件事合成一个"进来就顺手签了"的 GET，等于让刷新页面变成写操作——预取、爬虫、浏览器
 * 预连接都会替你"签"掉不该签的日子。
 */
@RestController
@RequestMapping("/api/user/me")
@RequiresLogin
public class SignApiController {

    private final SignService signService;
    private final PointsService pointsService;

    public SignApiController(SignService signService, PointsService pointsService) {
        this.signService = signService;
        this.pointsService = pointsService;
    }

    /** 今天签一下。重复调用不报错也不重复加分，回的是当前状态。 */
    @PostMapping("/sign")
    public Result<SignVOs.Result> sign() {
        return Result.ok(signService.sign(UserContext.requireUserId()));
    }

    /** 今天签没签、本月几天、连签几天、当前积分。 */
    @GetMapping("/sign/status")
    public Result<SignVOs.Status> status() {
        return Result.ok(signService.status(UserContext.requireUserId()));
    }

    /** 积分流水，按时间倒序。 */
    @GetMapping("/points")
    public Result<PageResult<SignVOs.PointsRow>> points(@RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "10") int pageSize) {
        return Result.ok(pointsService.pageMine(UserContext.requireUserId(), page, pageSize));
    }
}
