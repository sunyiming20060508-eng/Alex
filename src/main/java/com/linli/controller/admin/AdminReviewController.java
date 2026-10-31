package com.linli.controller.admin;

import com.linli.annotation.RequiresAdmin;
import com.linli.common.PageResult;
import com.linli.common.Result;
import com.linli.dto.AdminReviewQueryDTO;
import com.linli.dto.StatusForm;
import com.linli.service.AdminReviewService;
import com.linli.vo.AdminVOs;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端的评价列表与审核。评价是**先发后审**（见 V14），所以这里的动作只有"驳回"和"恢复通过"，
 * 没有"待我处理"那一档——想看待审的队列在本项目里等价于"看全部"。
 */
@RestController
@RequestMapping("/api/admin/reviews")
@RequiresAdmin
public class AdminReviewController {

    private final AdminReviewService adminReviewService;

    public AdminReviewController(AdminReviewService adminReviewService) {
        this.adminReviewService = adminReviewService;
    }

    /** 支持按状态（1 已通过 / 2 已驳回 / 不传=全部）、商家、正文关键词筛选。 */
    @GetMapping
    public Result<PageResult<AdminVOs.ReviewRow>> page(AdminReviewQueryDTO query) {
        return Result.ok(adminReviewService.page(query));
    }

    /** 驳回传 2、恢复通过传 1；驳回会连带重算这家商家的展示分并失效它的详情缓存。 */
    @PostMapping("/{id}/audit")
    public Result<AdminVOs.StatusResult> audit(@PathVariable Long id, @Valid @RequestBody StatusForm form) {
        return Result.ok(adminReviewService.audit(id, form.status()));
    }
}
