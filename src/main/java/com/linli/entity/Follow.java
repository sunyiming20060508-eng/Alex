package com.linli.entity;

import java.time.LocalDateTime;
import lombok.Data;

/** follow 表实体：一条"谁关注了谁"。取关是删行，不做软删——uk_follow 只有一条，软删会让重新关注失败。 */
@Data
public class Follow {

    private Long id;

    /** 关注者。 */
    private Long userId;

    /** 被关注者。 */
    private Long followUserId;

    private LocalDateTime createTime;
}
