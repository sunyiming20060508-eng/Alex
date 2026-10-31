package com.linli.dto;

import lombok.Data;

/**
 * 管理端笔记列表的查询条件。分页口径和 {@link BlogPageQueryDTO} 一致，不造第二套换算。
 *
 * 和用户端列表最大的区别：**这里要看得见被下架的那些**（{@code status} 传 0 或留空），
 * 所以它不能复用 {@code pageQuery}——那条 SQL 里写死了 {@code where b.status = 1}。
 */
@Data
public class AdminBlogQueryDTO {

    private Integer page = 1;

    /** 每页条数，上限 50。 */
    private Integer pageSize = 10;

    /** 1 只看展示中、0 只看已下架、null 只看全部。 */
    private Integer status;

    /** 标题或正文里的关键词；空表示不过滤。 */
    private String keyword;

    /** 只看某个作者（从用户详情跳过来时用）。 */
    private Long authorId;

    public int normalizedPage() {
        return page == null || page < 1 ? 1 : page;
    }

    public int normalizedPageSize() {
        if (pageSize == null || pageSize < 1) return 10;
        return Math.min(pageSize, 50);
    }

    /** 关键词去空格，让 XML 里的 if 只需要判断 null。 */
    public String trimmedKeyword() {
        if (keyword == null) return null;
        String value = keyword.trim();
        return value.isEmpty() ? null : value;
    }

    public int getOffset() {
        return (normalizedPage() - 1) * normalizedPageSize();
    }

    public int getLimit() {
        return normalizedPageSize();
    }
}
