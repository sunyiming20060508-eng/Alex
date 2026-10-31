package com.linli.vo;

import com.linli.common.TimeText;
import com.linli.entity.Notification;

/**
 * 站内消息返回对象。{@code read} 出去是布尔（前端只关心亮不亮点），库里是 0/1；
 * {@code typeText} 是那句中文动作，判断留在实体里，别在模板里再 switch 一次。
 */
public record NotificationVO(Long id, String type, String typeText, ContentVOs.Author from, Long targetId,
                             String summary, boolean read, String createdAt) {

    public static NotificationVO from(Notification notification) {
        return new NotificationVO(notification.getId(), notification.getType(), notification.typeText(),
                new ContentVOs.Author(notification.getFromUserId(), notification.getFromName(), notification.getFromIcon()),
                notification.getTargetId(), notification.getSummary(),
                Integer.valueOf(1).equals(notification.getIsRead()),
                TimeText.format(notification.getCreateTime()));
    }
}
