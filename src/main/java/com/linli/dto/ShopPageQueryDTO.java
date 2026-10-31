package com.linli.dto;

import lombok.Data;

/**
 * GET /api/shops 的查询条件。分页和关键词过滤都在 Mapper 的 XML 里做，
 * Controller 只负责把请求参数装进这个对象。
 */
@Data
public class ShopPageQueryDTO {

    /** 页码，从 1 开始。 */
    private Integer page = 1;

    /** 每页条数，上限 50，防止前端传个超大值把整表拉走。 */
    private Integer pageSize = 10;

    /** 商家名或分类名的模糊词，空值表示不过滤。 */
    private String keyword;

    /** 分类 id，来自 /api/categories；为空表示全部分类。 */
    private Long categoryId;

    /** 首页城市筛选；空表示不限城市。 */
    private String city;

    /**
     * 排序字段。白名单只有 distance（最近）/ score（最高）/ price（人均最低），
     * 传别的直接拒——静默回落到默认值就是"参数不生效"这个 bug 的成因，宁可报错也别装没事。
     * 列名写死在 XML 的 {@code <choose>} 里，这个值永远不进 SQL 文本，所以没有拼接注入面。
     */
    private String sort;

    public static final java.util.Set<String> SORTS = java.util.Set.of("distance", "score", "price");

    public int normalizedPage() {
        return page == null || page < 1 ? 1 : page;
    }

    public int normalizedPageSize() {
        if (pageSize == null || pageSize < 1) return 10;
        return Math.min(pageSize, 50);
    }

    /** MyBatis 里用 #{offset} 取到的就是这个派生属性。 */
    public int getOffset() {
        return (normalizedPage() - 1) * normalizedPageSize();
    }

    /** XML 里 limit 的第二个参数，避免直接写 #{pageSize} 之外的隐式换算。 */
    public int getLimit() {
        return normalizedPageSize();
    }

    /** 去掉首尾空格后的关键词，全空时返回 null，让 XML 的 if 判断走"不过滤"分支。 */
    public String trimmedKeyword() {
        if (keyword == null) return null;
        String value = keyword.trim();
        return value.isEmpty() ? null : value;
    }

    /** 排序字段的小写归一：没传就是 score。是否合法由 Service 按白名单判。 */
    /** 城市去空格，让 XML 里的 if 只看 null。 */
    public String trimmedCity() {
        if (city == null) return null;
        String value = city.trim();
        return value.isEmpty() ? null : value;
    }

    public String normalizedSort() {
        if (sort == null) return "score";
        String value = sort.trim().toLowerCase();
        return value.isEmpty() ? "score" : value;
    }
}
