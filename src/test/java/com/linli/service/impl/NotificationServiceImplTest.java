package com.linli.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linli.common.PageResult;
import com.linli.entity.Notification;
import com.linli.mapper.NotificationMapper;
import com.linli.vo.NotificationVO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 站内消息的单元测试。守两条：收件箱不该有"自己给自己"的消息，以及任何读写都出不了自己的收件箱。
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    private static final Long ME = 1L;
    private static final Long OTHER = 2L;
    private static final Long BLOG_ID = 9500L;

    @Mock private NotificationMapper notificationMapper;

    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NotificationServiceImpl(notificationMapper);
    }

    @Test
    @DisplayName("自己赞自己的笔记不进收件箱；缺任一侧身份也不写")
    void selfAndIncompleteEventsAreSkipped() {
        service.record(Notification.TYPE_LIKE_BLOG, ME, ME, BLOG_ID, null);
        service.record(Notification.TYPE_LIKE_BLOG, null, OTHER, BLOG_ID, null);
        service.record(Notification.TYPE_LIKE_BLOG, ME, null, BLOG_ID, null);
        service.record(null, ME, OTHER, BLOG_ID, null);

        verify(notificationMapper, never()).insert(any());
    }

    @Test
    @DisplayName("正常事件写一行：收件人是笔记作者，触发人是点赞的人")
    void eventWritesOneRowForTheAuthor() {
        service.record(Notification.TYPE_LIKE_BLOG, OTHER, ME, BLOG_ID, null);

        ArgumentCaptor<Notification> inserted = ArgumentCaptor.captor();
        verify(notificationMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getUserId()).isEqualTo(OTHER);
        assertThat(inserted.getValue().getFromUserId()).isEqualTo(ME);
        assertThat(inserted.getValue().getType()).isEqualTo(Notification.TYPE_LIKE_BLOG);
        assertThat(inserted.getValue().getTargetId()).isEqualTo(BLOG_ID);
    }

    @Test
    @DisplayName("评论摘要超长会截断：列是 VARCHAR(255)，评论允许 1000 字")
    void longSummaryIsTruncated() {
        service.record(Notification.TYPE_COMMENT_BLOG, OTHER, ME, BLOG_ID, "长".repeat(160));

        ArgumentCaptor<Notification> inserted = ArgumentCaptor.captor();
        verify(notificationMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getSummary()).hasSize(101).endsWith("…");
    }

    @Test
    @DisplayName("摘要两侧空白清掉，纯空白存成 null 而不是空串")
    void blankSummaryBecomesNull() {
        service.record(Notification.TYPE_COMMENT_BLOG, OTHER, ME, BLOG_ID, "   ");

        ArgumentCaptor<Notification> inserted = ArgumentCaptor.captor();
        verify(notificationMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getSummary()).isNull();
    }

    @Test
    @DisplayName("未读数为 0 时不再查列表，分页参数被收敛进合法区间")
    void emptyInboxSkipsTheListQuery() {
        when(notificationMapper.countByUser(ME, true)).thenReturn(0L);

        PageResult<NotificationVO> page = service.page(ME, 0, 999, true);

        assertThat(page.total()).isZero();
        assertThat(page.list()).isEmpty();
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.pageSize()).isEqualTo(50);
        verify(notificationMapper, never()).pageByUser(anyLong(), anyBoolean(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("VO 里带出动作中文、触发人和已读布尔；未知类型不抛错只回兜底文案")
    void voCarriesTextAuthorAndReadFlag() {
        Notification like = notification(Notification.TYPE_LIKE_BLOG, 0);
        Notification dirty = notification("SOMETHING_NEW", 1);
        when(notificationMapper.countByUser(ME, false)).thenReturn(2L);
        when(notificationMapper.pageByUser(ME, false, 0, 10)).thenReturn(List.of(like, dirty));

        PageResult<NotificationVO> page = service.page(ME, 1, 10, false);

        assertThat(page.list()).hasSize(2);
        assertThat(page.list().get(0).typeText()).isEqualTo("赞了你的笔记");
        assertThat(page.list().get(0).read()).isFalse();
        assertThat(page.list().get(0).from().nickname()).isEqualTo("阿岚");
        assertThat(page.list().get(1).typeText()).isEqualTo("通知了你");
        assertThat(page.list().get(1).read()).isTrue();
    }

    @Test
    @DisplayName("标记已读带 user_id 守卫：改不到别人收件箱里的消息")
    void markReadIsGuardedByOwner() {
        service.markRead(8800L, ME);

        verify(notificationMapper).markRead(8800L, ME);
    }

    @Test
    @DisplayName("没带 id 的标记请求不打库")
    void nullIdDoesNothing() {
        service.markRead(null, ME);

        verify(notificationMapper, never()).markRead(any(), any());
    }

    @Test
    @DisplayName("未读数就是 mapper 的 count，前端那一跳不查列表")
    void unreadCountIsTheCount() {
        when(notificationMapper.countUnread(ME)).thenReturn(7L);

        assertThat(service.unreadCount(ME)).isEqualTo(7);
    }

    @Test
    @DisplayName("全部已读返回改了几条，前端据此把红点归零")
    void markAllReadReportsHowMany() {
        when(notificationMapper.markAllRead(ME)).thenReturn(3);

        assertThat(service.markAllRead(ME)).isEqualTo(3);
    }

    private static Notification notification(String type, int isRead) {
        Notification notification = new Notification();
        notification.setId(8800L);
        notification.setUserId(ME);
        notification.setFromUserId(OTHER);
        notification.setType(type);
        notification.setTargetId(BLOG_ID);
        notification.setIsRead(isRead);
        notification.setFromName("阿岚");
        notification.setFromIcon("🌿");
        notification.setCreateTime(LocalDateTime.now());
        return notification;
    }
}
