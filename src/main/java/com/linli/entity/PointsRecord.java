package com.linli.entity;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * points_record 表实体：积分流水。
 *
 * {@code balance} 是**变化后的余额快照**，不是冗余：翻流水的人要能直接看到"这一笔之后我有多少分"，
 * 让他自己从前往后累加的话，只要中间漏一行，整页数字就全错了。
 */
@Data
public class PointsRecord {

    public static final String TYPE_SIGN = "SIGN";
    public static final String TYPE_SIGN_MONTH = "SIGN_MONTH";
    public static final String TYPE_ORDER = "ORDER";

    private Long id;

    private Long userId;

    /** 本次变化：正数加分、负数扣分。列名故意不叫 change——那是 MySQL 的关键字。 */
    private Integer delta;

    private Integer balance;

    private String type;

    private Long refId;

    private String content;

    private LocalDateTime createTime;
}
