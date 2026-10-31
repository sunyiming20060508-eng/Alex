package com.linli.entity;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * user_favorite 表实体：一人一店一条。
 * 收藏关系不存任何冗余计数，"这家店被收藏了多少次"现阶段界面不显示，等真要做再加列。
 */
@Data
public class UserFavorite {

    private Long id;

    private Long userId;

    private Long shopId;

    private LocalDateTime createTime;
}
