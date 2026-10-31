package com.linli.service.impl;

import com.linli.common.PageResult;
import com.linli.entity.Notification;
import com.linli.mapper.NotificationMapper;
import com.linli.service.NotificationService;
import com.linli.vo.NotificationVO;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.linli.common.Pagination;

/**
 * 站内消息的读写。
 *
 * 三件事值得写下来：
 *
 * 1. {@link #record} 上**没有** {@code @Transactional}。它是在点赞/评论/关注的业务事务里被调用的，
 *    自己不开新事务，所以消息和业务写是同生共死的：点赞成功了消息一定在，点赞回滚了消息也不会留下。
 *    反过来这也意味着"消息写失败会把点赞一起回滚"——本项目的取舍是接受它：写失败通常是库挂了，
 *    那时候点赞本来也保不住，不值得为附属数据引入"发件箱表 + 异步投递"那一整套。
 * 2. 收件箱只能本人读写，所以每个语句都带 {@code user_id = #{userId}} 守卫，不靠 Service 先查一遍。
 * 3. 已读只前进不回退（{@code is_read = 0} 才会被更新），因此没有"标为未读"接口，重复点击是 no-op。
 */
@Service
public class NotificationServiceImpl implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 50;

    private final NotificationMapper notificationMapper;

    public NotificationServiceImpl(NotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    @Override
    public void record(String type, Long toUserId, Long fromUserId, Long targetId, String summary) {
        if (type == null || toUserId == null || fromUserId == null || toUserId.equals(fromUserId)) {
            return;
        }
        Notification notification = new Notification();
        notification.setUserId(toUserId);
        notification.setFromUserId(fromUserId);
        notification.setType(type);
        notification.setTargetId(targetId);
        notification.setSummary(Notification.truncateSummary(summary));
        notificationMapper.insert(notification);
        log.debug("站内消息已记录: type={} to={} from={} target={}", type, toUserId, fromUserId, targetId);
    }

    @Override
    public PageResult<NotificationVO> page(Long userId, int page, int pageSize, boolean unreadOnly) {
        Pagination query = Pagination.of(page, pageSize, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        int safePage = query.page();
        int safeSize = query.size();
        long total = notificationMapper.countByUser(userId, unreadOnly);
        List<NotificationVO> list = total == 0 ? List.of()
                : notificationMapper.pageByUser(userId, unreadOnly, (safePage - 1) * safeSize, safeSize).stream()
                        .map(NotificationVO::from).toList();
        return new PageResult<>(list, total, safePage, safeSize);
    }

    @Override
    public long unreadCount(Long userId) {
        return notificationMapper.countUnread(userId);
    }

    @Override
    public void markRead(Long id, Long userId) {
        if (id == null) return;
        // 返回 0 有两种情况：已经读过、或者这条压根不是他的。两者都不该有可见后果，所以不区分、不报错
        notificationMapper.markRead(id, userId);
    }

    @Override
    public int markAllRead(Long userId) {
        return notificationMapper.markAllRead(userId);
    }
}
