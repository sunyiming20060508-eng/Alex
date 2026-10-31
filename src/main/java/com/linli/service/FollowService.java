package com.linli.service;

import com.linli.vo.ContentVOs.FollowResult;

/** 关注关系与关注 Feed。发布笔记时的推送动作也在这里，BlogService 只负责调用。 */
public interface FollowService {

    /** 关注某人；重复关注由唯一索引兜住，回当前状态而不是报错。 */
    FollowResult follow(Long targetUserId, Long userId);

    /** 取关；没关注过也返回成功状态，取消一个不存在的关注不该报错。 */
    FollowResult unfollow(Long targetUserId, Long userId);

    boolean isFollowing(Long userId, Long targetUserId);

    /**
     * 笔记发布后推给作者的每个粉丝（写进各自的收件箱 ZSet）。
     * score 用发布时间戳，收件箱按上限截断，只留最近若干条。
     */
    void pushToFollowers(Long authorId, Long blogId, long scoreSeconds);

    /**
     * 笔记被作者删掉之后，把它从每个粉丝的收件箱里摘掉，和 {@link #pushToFollowers} 成对。
     * 不摘也不会报错（读的时候按 id 回库、{@code status = 1} 过滤，死 id 自然被丢掉），
     * 但那格位置就一直空着：一页 10 条可能只回 6 条，而且硬删的行永远不会再回来。
     */
    void removeFromFollowers(Long authorId, Long blogId);
}
