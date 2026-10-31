package com.linli.vo;

import com.linli.entity.Category;

/** GET /api/categories 的返回项，id 是 category 表主键，可直接作为 /api/shops?categoryId= 的入参。 */
public record CategoryVO(Long id, String name) {

    public static CategoryVO from(Category category) {
        return category == null ? null : new CategoryVO(category.getId(), category.getName());
    }
}
