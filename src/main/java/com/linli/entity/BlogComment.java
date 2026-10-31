package com.linli.entity;

import java.time.LocalDateTime;
import lombok.Data;

/** blog_comment 表实体：笔记评论。一期不做嵌套回复，所以没有 parent_id。 */
@Data
public class BlogComment {

    private Long id;

    private Long blogId;

    private Long userId;

    private String content;

    private LocalDateTime createTime;

    // ---- 联表带出，不是本表列 ----

    private String authorName;

    private String authorIcon;

    /** "我的评论"列表用：这条评论挂在谁的哪篇笔记下（join blog + join 作者用户带出）。 */
    private String blogTitle;

    private String blogAuthorName;
}
