package com.linli.controller.user;

import com.linli.annotation.RequiresLogin;
import com.linli.common.PageResult;
import com.linli.common.Result;
import com.linli.context.UserContext;
import com.linli.service.FavoriteService;
import com.linli.vo.ShopVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家收藏。POST/DELETE 成对而不是一个"切换"接口：切换在并发下会算错最终状态
 * （两个请求都读到"未收藏"，一个加了另一个也加，界面显示的心和库里对不上），
 * 而"收藏"和"取消收藏"各自幂等，重试、连点、乱序都不需要额外处理。
 *
 * 返回的布尔值表示**这次有没有真的改动**，前端据此决定要不要提示"已收藏 / 已取消"，
 * 不拿它当业务状态用。登录由类上的 {@link RequiresLogin} 声明，用户 id 只从会话取。
 */
@RestController
@RequestMapping("/api")
@RequiresLogin
public class FavoriteApiController {

    private final FavoriteService favoriteService;

    public FavoriteApiController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @PostMapping("/shops/{id}/favorite")
    public Result<Boolean> add(@PathVariable Long id) {
        return Result.ok(favoriteService.add(UserContext.requireUserId(), id));
    }

    @DeleteMapping("/shops/{id}/favorite")
    public Result<Boolean> remove(@PathVariable Long id) {
        return Result.ok(favoriteService.remove(UserContext.requireUserId(), id));
    }

    /** 我的收藏，按收藏时间倒序，返回的就是能直接渲染的商家卡片。 */
    @GetMapping("/user/me/favorites")
    public Result<PageResult<ShopVO>> mine(@RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "10") int pageSize) {
        return Result.ok(favoriteService.pageMine(UserContext.requireUserId(), page, pageSize));
    }
}
