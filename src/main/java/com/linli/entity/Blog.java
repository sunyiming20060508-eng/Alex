package com.linli.entity;

import java.time.LocalDateTime;
import lombok.Data;

/** blog 表实体：探店笔记。作者信息不在本表，列表和详情都由 Service 一次 join 或批量补。 */
@Data
public class Blog {

    /** 1 正常；0 下架。一期没有管理端，列先留着给后面的审核用。 */
    public static final int STATUS_ONLINE = 1;
    public static final int STATUS_OFFLINE = 0;

    private Long id;

    private Long userId;

    /** 关联商家，纯生活记录可以为空。 */
    private Long shopId;

    private String title;

    private String content;

    /** 列表封面，现阶段和 shop.cover 一样用表情占位。 */
    private String cover;

    /** 冗余计数，同事务内随点赞/评论一起改，读列表时不再 count(*)。 */
    private Integer likeCount;

    private Integer commentCount;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    // ---- 以下都不是 blog 表的列，由联表或二次查询带出 ----

    /** 作者昵称。 */
    private String authorName;

    /** 作者头像（表情占位或上传地址）。 */
    private String authorIcon;

    private String shopName;

    /** 当前登录用户是否点过赞：列表页一次 IN 查询回填，没登录就是 false。 */
    private Boolean liked;

    public boolean online() {
        return status == null || status == STATUS_ONLINE;
    }
}
