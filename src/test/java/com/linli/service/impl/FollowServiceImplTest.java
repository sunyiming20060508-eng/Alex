package com.linli.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.linli.common.ApiException;
import com.linli.common.CacheTtlMatcher;
import com.linli.constant.RedisKeyConstant;
import com.linli.entity.Notification;
import com.linli.mapper.FollowMapper;
import com.linli.service.NotificationService;
import com.linli.vo.ContentVOs.FollowResult;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

/** 关注关系与"发布时推送收件箱"的单元测试。 */
@ExtendWith(MockitoExtension.class)
class FollowServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final Long TARGET_ID = 2L;

    @Mock private FollowMapper followMapper;
    @Mock private NotificationService notificationService;
    @Mock private StringRedisTemplate redis;
    @Mock private ZSetOperations<String, String> zSet;

    private FollowServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FollowServiceImpl(followMapper, notificationService, redis);
    }

    @Test
    @DisplayName("关注自己直接拒，不写库也不碰 Redis")
    void cannotFollowSelf() {
        assertFails(() -> service.follow(USER_ID, USER_ID), 40001, "不能关注自己");
        assertFails(() -> service.follow(null, USER_ID), 40001, "缺少要关注的用户 id");
        verifyNoInteractions(followMapper);
        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("连点两次关注：第二次由唯一索引接住，回\"已关注\"而不是 500")
    void duplicateFollowIsIdempotent() {
        when(followMapper.insert(USER_ID, TARGET_ID)).thenThrow(new DuplicateKeyException("uk_follow"));
        when(followMapper.exists(USER_ID, TARGET_ID)).thenReturn(1);
        when(followMapper.countFollowers(TARGET_ID)).thenReturn(7L);

        FollowResult result = service.follow(TARGET_ID, USER_ID);

        assertThat(result.following()).isTrue();
        // 数字是"目标的粉丝数"，不是我自己关注了多少人
        assertThat(result.followerCount()).isEqualTo(7);
        // 第二次没真插进行，就不该给对方多推一条"有人关注了你"
        verify(notificationService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("关注同样要作废收件箱：新关注那个人**以前**发的笔记没人推过，只能靠下一次读重建")
    void followDropsTheInboxForRebuild() {
        when(followMapper.exists(USER_ID, TARGET_ID)).thenReturn(1);
        when(followMapper.countFollowers(TARGET_ID)).thenReturn(7L);

        service.follow(TARGET_ID, USER_ID);

        verify(redis).delete(RedisKeyConstant.followFeed(USER_ID));
        verify(redis).delete(RedisKeyConstant.feedEmptyMarker(USER_ID));
        verify(notificationService).record(Notification.TYPE_FOLLOW, TARGET_ID, USER_ID, TARGET_ID, null);
    }

    @Test
    @DisplayName("取关一个没关注的对象也算成功，状态回\"未关注\"")
    void unfollowIsIdempotent() {
        when(followMapper.delete(USER_ID, TARGET_ID)).thenReturn(0);
        when(followMapper.exists(USER_ID, TARGET_ID)).thenReturn(0);

        FollowResult result = service.unfollow(TARGET_ID, USER_ID);

        assertThat(result.following()).isFalse();
    }

    @Test
    @DisplayName("取关要顺手丢掉整个收件箱：否则那个人之前推来的笔记会一直留在关注流里")
    void unfollowDropsTheInboxForRebuild() {
        when(followMapper.delete(USER_ID, TARGET_ID)).thenReturn(1);
        when(followMapper.exists(USER_ID, TARGET_ID)).thenReturn(0);

        service.unfollow(TARGET_ID, USER_ID);

        verify(redis).delete(RedisKeyConstant.followFeed(USER_ID));
        verify(redis).delete(RedisKeyConstant.feedEmptyMarker(USER_ID));
    }

    @Test
    @DisplayName("没粉丝的作者发笔记，一次 Redis 都不碰")
    void pushSkippedWithoutFollowers() {
        when(followMapper.listFollowers(USER_ID)).thenReturn(List.of());

        service.pushToFollowers(USER_ID, 100L, 1_700_000_000L);

        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("推送给每个粉丝：写自己的收件箱、按上限截断、续期、清掉\"空 Feed\"占位")
    void pushWritesEveryFollowerInbox() {
        when(followMapper.listFollowers(TARGET_ID)).thenReturn(List.of(USER_ID, 3L));
        when(redis.opsForZSet()).thenReturn(zSet);
        String mine = RedisKeyConstant.followFeed(USER_ID);
        String other = RedisKeyConstant.followFeed(3L);

        service.pushToFollowers(TARGET_ID, 100L, 1_700_000_000L);

        verify(zSet).add(mine, "100", 1_700_000_000d);
        verify(zSet).add(other, "100", 1_700_000_000d);
        // 截断和续期都要按每个收件箱各做一次，不能只处理第一个粉丝
        verify(zSet).removeRange(mine, 0, -1 - RedisKeyConstant.FOLLOW_FEED_MAX);
        verify(zSet).removeRange(other, 0, -1 - RedisKeyConstant.FOLLOW_FEED_MAX);
        verify(redis).expire(eq(mine), CacheTtlMatcher.jittered(RedisKeyConstant.FOLLOW_FEED_TTL_SECONDS));
        verify(redis).delete(RedisKeyConstant.feedEmptyMarker(USER_ID));
        verify(redis).delete(RedisKeyConstant.feedEmptyMarker(3L));
    }

    @Test
    @DisplayName("作者删笔记：按同一份粉丝名单，把这一条从各自收件箱里 ZREM 掉")
    void removeFromFollowersZRemovesEveryInbox() {
        when(followMapper.listFollowers(TARGET_ID)).thenReturn(List.of(USER_ID, 3L));
        when(redis.opsForZSet()).thenReturn(zSet);

        service.removeFromFollowers(TARGET_ID, 100L);

        verify(zSet).remove(RedisKeyConstant.followFeed(USER_ID), "100");
        verify(zSet).remove(RedisKeyConstant.followFeed(3L), "100");
        // 只摘这一个成员：整份收件箱作废会让粉丝其它人的笔记也一起没了，下一次读还要全量回库
        verify(zSet, never()).removeRange(any(), anyLong(), anyLong());
        verify(redis, never()).delete(RedisKeyConstant.followFeed(USER_ID));
        verify(redis, never()).delete(RedisKeyConstant.followFeed(3L));
    }

    @Test
    @DisplayName("没粉丝的作者删笔记，一次 Redis 都不碰（和推送那条对称）")
    void removeFromFollowersSkippedWithoutFollowers() {
        when(followMapper.listFollowers(USER_ID)).thenReturn(List.of());

        service.removeFromFollowers(USER_ID, 100L);

        verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("id 缺失时直接返回，不拿 null 去拼 Redis key")
    void removeFromFollowersGuardsNulls() {
        service.removeFromFollowers(null, 100L);
        service.removeFromFollowers(TARGET_ID, null);

        verifyNoInteractions(redis);
        verify(followMapper, never()).listFollowers(any());
    }

    @Test
    @DisplayName("isFollowing 在任一侧为空时直接 false，不查库")
    void isFollowingGuardsNulls() {
        assertThat(service.isFollowing(null, TARGET_ID)).isFalse();
        assertThat(service.isFollowing(USER_ID, null)).isFalse();
        verify(followMapper, never()).exists(any(), any());
    }

    private static void assertFails(Runnable call, int code, String messagePart) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code))
                .hasMessageContaining(messagePart);
    }
}
