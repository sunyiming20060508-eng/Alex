package com.linli.controller.user;

import com.linli.annotation.RequiresLogin;
import com.linli.common.Result;
import com.linli.context.UserContext;
import com.linli.service.FollowService;
import com.linli.vo.ContentVOs.FollowResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关注接口。POST 是关注、DELETE 是取关，两边都幂等：重复关注回"已关注"而不是报错，
 * 取关一个没关注的对象也回"未关注"。真正的防重由 follow 表的 uk_follow 兜底。
 */
@RestController
@RequestMapping("/api/users")
@RequiresLogin
public class FollowApiController {

    private final FollowService followService;

    public FollowApiController(FollowService followService) { this.followService = followService; }

    @PostMapping("/{id}/follow")
    public Result<FollowResult> follow(@PathVariable("id") Long id) {
        return Result.ok(followService.follow(id, UserContext.requireUserId()));
    }

    @DeleteMapping("/{id}/follow")
    public Result<FollowResult> unfollow(@PathVariable("id") Long id) {
        return Result.ok(followService.unfollow(id, UserContext.requireUserId()));
    }
}
