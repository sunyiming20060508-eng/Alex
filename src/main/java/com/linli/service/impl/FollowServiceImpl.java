package com.linli.service.impl;

import com.linli.common.ApiException;
import com.linli.common.CacheTtl;
import com.linli.common.ErrorCode;
import com.linli.constant.RedisKeyConstant;
import com.linli.entity.Notification;
import com.linli.mapper.FollowMapper;
import com.linli.service.FollowService;
import com.linli.service.NotificationService;
import com.linli.vo.ContentVOs.FollowResult;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 关注关系 + 关注 Feed 的"写"这一半（推模式）。
 *
 * 为什么关注 Feed 用推：本项目是本地生活内容场景，读多写少且粉丝量级小（一个人关注几十个作者），
 * 发布时写 N 份收件箱的成本远低于每次打开 Feed 都去 join follow + blog 排序。
 * 反过来"大 V 有百万粉丝"就得改成拉模式或混合模式——那一步现在做是负优化，
 * 因为没有任何数据支撑这个量级，写出来只是为了看起来像架构。
 */
@Service
public class FollowServiceImpl implements FollowService {

    private static final Logger log = LoggerFactory.getLogger(FollowServiceImpl.class);

    private final FollowMapper followMapper;
    private final NotificationService notificationService;
    private final StringRedisTemplate redis;

    public FollowServiceImpl(FollowMapper followMapper, NotificationService notificationService,
                             StringRedisTemplate redis) {
        this.followMapper = followMapper;
        this.notificationService = notificationService;
        this.redis = redis;
    }

    @Override
    @Transactional
    public FollowResult follow(Long targetUserId, Long userId) {
        if (targetUserId == null) throw new ApiException(ErrorCode.PARAM_INVALID, "缺少要关注的用户 id");
        if (targetUserId.equals(userId)) {
            throw new ApiException(ErrorCode.PARAM_INVALID, "不能关注自己");
        }
        try {
            followMapper.insert(userId, targetUserId);
            // 被关注的人收到一条消息；targetId 放被关注者（也就是收件人自己），前端据此跳去他的笔记列表
            notificationService.record(Notification.TYPE_FOLLOW, targetUserId, userId, targetUserId, null);
        } catch (DuplicateKeyException e) {
            // 连点两次关注：第二次的结果和第一次一样，不该报错，也不该再发一条消息
            log.debug("重复关注: userId={} target={}", userId, targetUserId);
        }
        // 关注关系一变，收件箱就不能再信：新关注的那个人**以前**发的笔记没人推过，
        // 只靠发布时的推送会永远漏掉它们。丢掉收件箱，下一次读按新的关注列表重建。
        invalidateFeed(userId);
        return result(targetUserId, userId);
    }

    @Override
    @Transactional
    public FollowResult unfollow(Long targetUserId, Long userId) {
        if (targetUserId == null) throw new ApiException(ErrorCode.PARAM_INVALID, "缺少要取关的用户 id");
        followMapper.delete(userId, targetUserId);
        // 推模式的固有代价：收件箱里那个人发的旧笔记还躺着。取关时同样直接丢掉整个收件箱，
        // 下一次读按新的关注列表重建——比"逐条找出是谁发的再删"便宜，也不会漏。
        invalidateFeed(userId);
        return result(targetUserId, userId);
    }

    /** 收件箱和"这个人的 Feed 是空的"占位一起作废：少删后者，取关/关注之后要等一分钟才看得见变化。 */
    private void invalidateFeed(Long userId) {
        redis.delete(RedisKeyConstant.followFeed(userId));
        redis.delete(RedisKeyConstant.feedEmptyMarker(userId));
    }

    @Override
    public boolean isFollowing(Long userId, Long targetUserId) {
        if (userId == null || targetUserId == null) return false;
        return followMapper.exists(userId, targetUserId) > 0;
    }

    @Override
    public void pushToFollowers(Long authorId, Long blogId, long scoreSeconds) {
        List<Long> followers = followMapper.listFollowers(authorId);
        if (followers.isEmpty()) return;
        String member = String.valueOf(blogId);
        for (Long followerId : followers) {
            String key = RedisKeyConstant.followFeed(followerId);
            redis.opsForZSet().add(key, member, scoreSeconds);
            // 收件箱只留最近 FOLLOW_FEED_MAX 条：按分数升序把最老的削掉
            redis.opsForZSet().removeRange(key, 0, -1 - RedisKeyConstant.FOLLOW_FEED_MAX);
            redis.expire(key, CacheTtl.of(RedisKeyConstant.FOLLOW_FEED_TTL_SECONDS));
            // 关注对象发了新内容，"这个人的 Feed 是空的"这个占位就该失效
            redis.delete(RedisKeyConstant.feedEmptyMarker(followerId));
        }
        log.info("笔记已推送关注 Feed: blogId={} authorId={} 粉丝数={}", blogId, authorId, followers.size());
    }

    /**
     * 和 pushToFollowers 成对：按同一份粉丝名单，把这条笔记从各自的收件箱里摘掉。
     * 只 ZREM 这一个成员，不动整份收件箱、也不动"这个人的 Feed 是空的"那个占位
     * ——摘除只会让收件箱变短，不会让一份空收件箱突然有内容。
     */
    @Override
    public void removeFromFollowers(Long authorId, Long blogId) {
        if (authorId == null || blogId == null) return;
        List<Long> followers = followMapper.listFollowers(authorId);
        if (followers.isEmpty()) return;
        String member = String.valueOf(blogId);
        for (Long followerId : followers) {
            redis.opsForZSet().remove(RedisKeyConstant.followFeed(followerId), member);
        }
        log.info("笔记已从关注 Feed 摘除: blogId={} authorId={} 粉丝数={}", blogId, authorId, followers.size());
    }

    private FollowResult result(Long targetUserId, Long userId) {
        return new FollowResult(isFollowing(userId, targetUserId), followMapper.countFollowers(targetUserId));
    }
}
