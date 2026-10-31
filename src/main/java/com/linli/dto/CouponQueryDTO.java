package com.linli.dto;

import lombok.Data;

/**
 * GET /api/coupons 的查询条件。分页口径与 ShopPageQueryDTO 一致，避免同一个项目里两套换算。
 * userId 只用来算"当前用户是否已领过"，未登录时为 null，SQL 里的 exists 子查询会直接给 0。
 */
@Data
public class CouponQueryDTO {

    private Integer page = 1;

    /** 每页条数，上限 50。 */
    private Integer pageSize = 10;

    /** 只看某家商家的券，为空表示全部商家。 */
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
