package com.linli.dto;

import lombok.Data;

/**
 * 管理端评价列表的查询条件。分页口径和 {@link BlogPageQueryDTO} 一致。
 *
 * {@code status} 传 2 就是"看被驳回的"，留空是全部——审核台第一件事是看见全部，
 * 只看待审的那一档在本项目里不存在（评价是先发后审，见 V14）。
 */
@Data
public class AdminReviewQueryDTO {

    private Integer page = 1;

    /** 每页条数，上限 50。 */
    private Integer pageSize = 10;

    /** 1 已通过、2 已驳回、null 全部。 */
    private Integer status;

    /** 只看某家商家的评价。 */
    private Long shopId;

    /** 评价正文里的关键词。 */
    private String keyword;

    public int normalizedPage() {
        return page == null || page < 1 ? 1 : page;
    }

    public int normalizedPageSize() {
        if (pageSize == null || pageSize < 1) return 10;
        return Math.min(pageSize, 50);
    }

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
