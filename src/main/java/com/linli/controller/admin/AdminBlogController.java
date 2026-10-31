package com.linli.controller.admin;

import com.linli.annotation.RequiresAdmin;
import com.linli.common.PageResult;
import com.linli.common.Result;
import com.linli.dto.AdminBlogQueryDTO;
import com.linli.dto.StatusForm;
import com.linli.service.AdminBlogService;
import com.linli.vo.AdminVOs;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端的笔记列表与下架/恢复。类上标 {@link RequiresAdmin}，这个 Controller 的每个接口都要求
 * 管理员身份（{@code user.role=1}）——不是管理员回 40301、未登录回 40101，两者都是服务端判的。
 *
 * 路径前缀单独用 {@code /api/admin}，是为了让"这是另一套受众"这件事写在 URL 上：
 * 网关、日志、以后的限流策略都能按前缀区分，而不必读代码才知道哪个接口是给运营用的。
 */
@RestController
@RequestMapping("/api/admin/blogs")
@RequiresAdmin
public class AdminBlogController {

    private final AdminBlogService adminBlogService;

    public AdminBlogController(AdminBlogService adminBlogService) {
        this.adminBlogService = adminBlogService;
    }

    /** 支持按状态（1 展示中 / 0 已下架 / 不传=全部）、作者、关键词筛选。 */
    @GetMapping
    public Result<PageResult<AdminVOs.BlogRow>> page(AdminBlogQueryDTO query) {
        return Result.ok(adminBlogService.page(query));
    }

    /** 下架传 0、恢复传 1。幂等：对已经下架的笔记再下架一次也是 200，状态不变。 */
    @PostMapping("/{id}/status")
    public Result<AdminVOs.StatusResult> updateStatus(@PathVariable Long id, @Valid @RequestBody StatusForm form) {
        return Result.ok(adminBlogService.updateStatus(id, form.status()));
    }
}
