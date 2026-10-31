package com.linli.service;

import com.linli.common.PageResult;
import com.linli.vo.NotificationVO;

/**
 * 站内消息。写的一侧是各业务事件（点赞 / 评论 / 关注）在**自己的事务里**顺手记一条；
 * 读的一侧只有收件箱本人能读。
 */
public interface NotificationService {

    /**
     * 记一条消息。{@code toUserId} 与 {@code fromUserId} 相同（自己赞自己的笔记）或任一为空时静默跳过——
     * 自己给自己发提醒没有意义，而"跳过"不该让主流程失败。
     */
    void record(String type, Long toUserId, Long fromUserId, Long targetId, String summary);

    PageResult<NotificationVO> page(Long userId, int page, int pageSize, boolean unreadOnly);

    long unreadCount(Long userId);

    void markRead(Long id, Long userId);

    /** 全部标记已读，返回改了几条。 */
    int markAllRead(Long userId);
}
