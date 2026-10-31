package com.linli.entity;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * review 表实体（商家评价）。
 *
 * 一条评价必须挂在**一单**上：{@code order_id} + {@code uk_review_order} 就是"一人一单只能评一次"这条
 * 业务规矩在库里的落点，和 blog_like / follow 是同一个思路——正确性交给唯一索引，Service 只负责把
 * 重复翻译成当前状态。authorName / authorIcon 是联表带出来的非列字段（和 Blog 一样，不再为每条评价查一次用户）。
 */
@Data
public class Review {

    private Long id;
    /** 评价人，只从会话里取。 */
    private Long userId;
    private Long shopId;
    /** 评的是哪一单；必须是这个用户名下已完成的订单。 */
    private Long orderId;
    private String content;
    /** 1-5 星。 */
    private Integer score;
    /** 逗号分隔的本平台图片地址，对外由 ReviewVO 拆成数组。 */
    private String images;

    /** 审核状态：1 已通过（展示且计入展示分）、2 已驳回。V14 加的列，默认 1，历史评价一律视为已通过。 */
    private Integer status;

    private LocalDateTime createTime;

    private String authorName;
    private String authorIcon;
    /** 联表带出的非列字段：管理台要按商家筛评价，列表上得知道这条是谁家的。 */
    private String shopName;
}
