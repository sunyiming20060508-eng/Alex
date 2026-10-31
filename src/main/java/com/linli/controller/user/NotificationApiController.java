package com.linli.controller.user;

import com.linli.annotation.RequiresLogin;
import com.linli.common.PageResult;
import com.linli.common.Result;
import com.linli.context.UserContext;
import com.linli.service.NotificationService;
import com.linli.vo.NotificationVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的消息。整个收件箱都是私密的，所以 {@code @RequiresLogin} 标在类上，
 * 用户 id 一律从会话取——不存在"看别人的未读数"这种接口。
 */
@RestController
@RequestMapping("/api/notifications")
@RequiresLogin
public class NotificationApiController {

    private final NotificationService notificationService;

    public NotificationApiController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public Result<PageResult<NotificationVO>> page(@RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "10") int pageSize,
                                                   @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return Result.ok(notificationService.page(UserContext.requireUserId(), page, pageSize, unreadOnly));
    }

    /** 顶栏轮询的那个数。单独一个接口是为了让这一跳只查一条 count，不把列表也拖出来。 */
    @GetMapping("/unread-count")
    public Result<Long> unreadCount() {
        return Result.ok(notificationService.unreadCount(UserContext.requireUserId()));
    }

    @PostMapping("/{id}/read")
    public Result<Void> markRead(@PathVariable("id") Long id) {
        notificationService.markRead(id, UserContext.requireUserId());
        return Result.ok(null);
    }

    /** 返回改了几条，前端据此把红点归零。 */
    @PostMapping("/read-all")
    public Result<Integer> markAllRead() {
        return Result.ok(notificationService.markAllRead(UserContext.requireUserId()));
    }
}
