package com.linli.entity;

import java.time.LocalDateTime;
import lombok.Data;

/** category 表实体，对应发现页顶部的分类筛选条。 */
@Data
public class Category {

    private Long id;

    private String name;

    /** 排序值，越小越靠前。 */
    private Integer sort;

    /** 1 显示，0 隐藏。 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
