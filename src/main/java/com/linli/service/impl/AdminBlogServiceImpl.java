package com.linli.service.impl;

import com.linli.common.ApiException;
import com.linli.common.ErrorCode;
import com.linli.common.PageResult;
import com.linli.context.UserContext;
import com.linli.dto.AdminBlogQueryDTO;
import com.linli.entity.Blog;
import com.linli.mapper.BlogMapper;
import com.linli.service.AdminBlogService;
import com.linli.vo.AdminVOs;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 笔记下架的实现。
 *
 * 用户端**不需要跟着改**：{@code BlogMapper} 里所有面向用户的读语句都写死了
 * {@code where b.status = 1}（pageQuery / listByIds / listRecentByAuthors / getOnlineById），
 * 所以 status 一改成 0，笔记立刻从推荐流、关注 Feed、商家详情和笔记详情里消失。
 * 关注 Feed 的 Redis 收件箱里可能还留着它的 id——那条会在读取时被 {@code listByIds} 的状态条件滤掉，
 * 所以最坏情况是"翻页时少一条"，不会出现点进去还能看的内容。
 */
@Service
public class AdminBlogServiceImpl implements AdminBlogService {

    private static final Logger log = LoggerFactory.getLogger(AdminBlogServiceImpl.class);

    /** 只有这两个取值有意义：0 下架、1 展示。传 5 进来不是"另一种状态"，是客户端写错了。 */
    private static final Set<Integer> STATUSES = Set.of(0, 1);

    private final BlogMapper blogMapper;

    public AdminBlogServiceImpl(BlogMapper blogMapper) {
        this.blogMapper = blogMapper;
    }

    @Override
    public PageResult<AdminVOs.BlogRow> page(AdminBlogQueryDTO query) {
        AdminBlogQueryDTO condition = normalize(query);
        long total = blogMapper.countAdmin(condition);
        List<AdminVOs.BlogRow> list = total == 0 ? List.of()
                : blogMapper.pageAdmin(condition).stream().map(AdminVOs.BlogRow::from).toList();
        return new PageResult<>(list, total, condition.normalizedPage(), condition.normalizedPageSize());
    }

    @Override
    public AdminVOs.StatusResult updateStatus(Long id, Integer status) {
        if (status == null || !STATUSES.contains(status)) {
            throw new ApiException(ErrorCode.PARAM_INVALID, "status 只支持 0（下架）和 1（恢复展示）");
        }
        Blog blog = id == null ? null : blogMapper.getAnyById(id);
        if (blog == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "笔记不存在");
        }
        blogMapper.updateStatus(id, status);
        log.debug("笔记状态已变更: blogId={} status={} 操作人={}", id, status, UserContext.userIdOrNull());
        return new AdminVOs.StatusResult(id, status, status == 0 ? "已下架" : "展示中");
    }

    /** 关键词去空格，让 XML 里的 if 只看 null；分页归一交给 DTO 自己。 */
    private AdminBlogQueryDTO normalize(AdminBlogQueryDTO query) {
        AdminBlogQueryDTO condition = query == null ? new AdminBlogQueryDTO() : query;
        condition.setKeyword(condition.trimmedKeyword());
        if (condition.getStatus() != null && !STATUSES.contains(condition.getStatus())) {
            throw new ApiException(ErrorCode.PARAM_INVALID, "status 只支持 0（已下架）或 1（展示中）");
        }
        return condition;
    }
}
