package com.linli.dto;

import lombok.Data;

/** GET /api/orders 的分页条件。status 为 null 表示全部状态。 */
@Data
public class OrderPageQueryDTO {

    private Integer page = 1;

    private Integer pageSize = 10;

    private Integer status;

    /** 由 Service 从登录上下文填进来，Mapper 里作为强制条件，越权查询在 SQL 层就查不到行。 */
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
