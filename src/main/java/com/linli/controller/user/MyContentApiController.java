package com.linli.controller.user;

import com.linli.annotation.RequiresLogin;
import com.linli.common.PageResult;
import com.linli.common.Result;
import com.linli.context.UserContext;
import com.linli.service.BlogService;
import com.linli.service.ReviewService;
import com.linli.vo.MineVOs;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * "我的内容"三张列表：我发的笔记、我发的评论、我写的评价，外加**删自己的笔记**和**删自己的评价**
 * 两个删除出口（都是硬删，归属只认会话）。
 *
 * 为什么不复用现成的公开接口：
 * <ul>
 *   <li>{@code GET /api/blogs?authorId=} 写死了 {@code status = 1}，作者被下架的笔记在里面看不见；
 *       而"我发的东西怎么不见了"恰恰是本人最该知道的一件事。</li>
 *   <li>评论只有"某篇笔记下的评论"这一个读法，没有"我发过的评论"。</li>
 *   <li>评价只有"某家商家下的评价"，同理。</li>
 * </ul>
 * 三条都按会话取 userId，请求里不出现 userId——和收藏、地址、浏览记录是同一个口径。
 */
@RestController
@RequestMapping("/api/user/me")
@RequiresLogin
public class MyContentApiController {

    private final BlogService blogService;
    private final ReviewService reviewService;

    public MyContentApiController(BlogService blogService, ReviewService reviewService) {
        this.blogService = blogService;
        this.reviewService = reviewService;
    }

    /** 我发的笔记，含已下架的（每行带 status 与 statusText）。 */
    @GetMapping("/blogs")
    public Result<PageResult<MineVOs.BlogRow>> blogs(@RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "10") int pageSize) {
        return Result.ok(blogService.pageMine(UserContext.requireUserId(), page, pageSize));
    }

    /** 我发出的评论，带它挂在谁的哪篇笔记下。 */
    @GetMapping("/comments")
    public Result<PageResult<MineVOs.CommentRow>> comments(@RequestParam(defaultValue = "1") int page,
                                                           @RequestParam(defaultValue = "10") int pageSize) {
        return Result.ok(blogService.myComments(UserContext.requireUserId(), page, pageSize));
    }

    /** 我写的评价，含被驳回的。 */
    @GetMapping("/reviews")
    public Result<PageResult<MineVOs.ReviewRow>> reviews(@RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "10") int pageSize) {
        return Result.ok(reviewService.pageMine(UserContext.requireUserId(), page, pageSize));
    }

    /** 删掉自己的笔记：硬删，连带清掉它的图片、点赞和评论（不可恢复，所以前端要红色二次确认）。 */
    @DeleteMapping("/blogs/{id}")
    public Result<Void> deleteBlog(@PathVariable Long id) {
        blogService.deleteMine(UserContext.requireUserId(), id);
        return Result.ok(null);
    }

    /** 删掉自己写的评价：连带重算展示分，那一单会重新变成可评价。 */
    @DeleteMapping("/reviews/{id}")
    public Result<Void> deleteReview(@PathVariable Long id) {
        reviewService.deleteMine(UserContext.requireUserId(), id);
        return Result.ok(null);
    }
}
