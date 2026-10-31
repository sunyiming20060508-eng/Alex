package com.linli.dto;

import lombok.Data;

/**
 * 笔记列表的查询条件。分页口径和 CouponQueryDTO / ShopPageQueryDTO 一致，不在同一个项目里造第二套换算。
 * userId 只用来算"当前用户是否点过赞"，未登录时为 null，SQL 里的 exists 子查询直接给 0。
 */
@Data
public class BlogPageQueryDTO {

    private Integer page = 1;

    /** 每页条数，上限 50。 */
    private Integer pageSize = 10;

    /** 只看某个作者的笔记（个人主页用），为空表示全部。 */
    private Long authorId;

    /** 只看关联某家商家的笔记（商家详情页的探店区块用）。 */
    private Long shopId;

    private Long userId;

    public int normalizedPage() {
        return page == null || page < 1 ? 1 : page;
    }

    public int normalizedPageSize() {
        if (pageSize == null || pageSize < 1) return 10;
        return Math.min(pageSize, 50);
    }

    public int getOffset() {
        return (normalizedPage() - 1) * normalizedPageSize();
    }

    public int getLimit() {
        return normalizedPageSize();
    }
}
