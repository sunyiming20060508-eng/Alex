package com.linli.controller.user;

import com.linli.annotation.RequiresLogin;
import com.linli.common.Result;
import com.linli.context.UserContext;
import com.linli.service.HistoryService;
import com.linli.vo.ShopVO;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的浏览记录。写入不在这里——商家详情 {@code GET /api/shops/{id}} 读的时候顺手记一条，
 * 所以这个类只有"读"和"删"，没有 POST：用户不该为了留下足迹再调一个接口。
 */
@RestController
@RequestMapping("/api/user/me/history")
@RequiresLogin
public class HistoryApiController {

    private final HistoryService historyService;

    public HistoryApiController(HistoryService historyService) {
        this.historyService = historyService;
    }

    /** 最近看过的商家，按浏览时间倒序，默认 10 条、最多 50 条。 */
    @GetMapping
    public Result<List<ShopVO>> latest(@RequestParam(defaultValue = "10") int limit) {
        return Result.ok(historyService.latest(UserContext.requireUserId(), limit));
    }

    /**
     * 一共多少条。个人中心那一格用它而不是"拉列表数长度"，因为列表接口有 {@code limit} 上限
     * （最多 50 条），拿列表长度当总数会在记录多的时候少报。
     */
    @GetMapping("/count")
    public Result<Long> count() {
        return Result.ok(historyService.count(UserContext.requireUserId()));
    }

    /** 清空。隐私边界：用户说不要了就得真没有。 */
    @DeleteMapping
    public Result<Void> clear() {
        historyService.clear(UserContext.requireUserId());
        return Result.ok(null);
    }
}
