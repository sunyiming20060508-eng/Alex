package com.linli.controller.user;

import com.linli.annotation.RequiresLogin;
import com.linli.common.PageResult;
import com.linli.common.Result;
import com.linli.context.UserContext;
import com.linli.dto.BlogPageQueryDTO;
import com.linli.dto.BlogPublishDTO;
import com.linli.dto.CommentDTO;
import com.linli.service.BlogService;
import com.linli.vo.ContentVOs.BlogDetail;
import com.linli.vo.ContentVOs.BlogItem;
import com.linli.vo.ContentVOs.Comment;
import com.linli.vo.ContentVOs.Feed;
import com.linli.vo.ContentVOs.LikeResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 探店笔记接口。列表和详情匿名可看，发布/点赞/评论要登录——所以 {@code @RequiresLogin} 标在方法上
 * 而不是类上。匿名请求也能带令牌进来：LoginContextInterceptor 有令牌就解析， liked 因此能算出来。
 */
@RestController
@RequestMapping("/api/blogs")
public class BlogApiController {

    private final BlogService blogService;

    public BlogApiController(BlogService blogService) { this.blogService = blogService; }

    /** 推荐流：按时间倒序分页，可按作者或商家过滤。 */
    @GetMapping
    public Result<PageResult<BlogItem>> page(@RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "10") int pageSize,
                                             @RequestParam(required = false) Long authorId,
                                             @RequestParam(required = false) Long shopId) {
        BlogPageQueryDTO query = new BlogPageQueryDTO();
        query.setPage(page);
        query.setPageSize(pageSize);
        query.setAuthorId(authorId);
        query.setShopId(shopId);
        return Result.ok(blogService.page(query, UserContext.userIdOrNull()));
    }

    /** 关注 Feed：Redis ZSet 收件箱，滚动分页——offset 传上一页返回的 nextOffset。 */
    @GetMapping("/feed/follow")
    @RequiresLogin
    public Result<Feed> followFeed(@RequestParam(defaultValue = "10") int pageSize,
                                   @RequestParam(required = false) Long offset) {
        return Result.ok(blogService.followFeed(UserContext.requireUserId(), offset, pageSize));
    }

    @GetMapping("/{id}")
    public Result<BlogDetail> detail(@PathVariable("id") Long id) {
        return Result.ok(blogService.detail(id, UserContext.userIdOrNull()));
    }

    @PostMapping
    @RequiresLogin
    public Result<BlogDetail> publish(@Valid @RequestBody BlogPublishDTO form) {
        return Result.ok(blogService.publish(form, UserContext.requireUserId()));
    }

    @PostMapping("/{id}/like")
    @RequiresLogin
    public Result<LikeResult> like(@PathVariable("id") Long id) {
        return Result.ok(blogService.like(id, UserContext.requireUserId()));
    }

    @DeleteMapping("/{id}/like")
    @RequiresLogin
    public Result<LikeResult> unlike(@PathVariable("id") Long id) {
        return Result.ok(blogService.unlike(id, UserContext.requireUserId()));
    }

    @GetMapping("/{id}/comments")
    public Result<PageResult<Comment>> comments(@PathVariable("id") Long id,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "10") int pageSize) {
        return Result.ok(blogService.comments(id, page, pageSize));
    }

    @PostMapping("/{id}/comments")
    @RequiresLogin
    public Result<Comment> comment(@PathVariable("id") Long id, @Valid @RequestBody CommentDTO form) {
        return Result.ok(blogService.comment(id, UserContext.requireUserId(), form));
    }
}
