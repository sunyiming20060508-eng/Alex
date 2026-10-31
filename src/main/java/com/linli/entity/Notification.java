package com.linli.entity;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * notification 表实体（站内消息）。
 *
 * 一条消息 = "谁（fromUserId）对我（userId）做了什么事（type），点进去跳到哪儿（targetId）"。
 * 它是**事件流水**而不是权威数据：点赞数、评论数、粉丝数仍然从各自的表算，这里只存"该不该提醒我看"。
 * 所以本表可以整张清空而不丢业务数据——和关注 Feed 那个 ZSet 是同一个定位。
 */
@Data
public class Notification {

    public static final String TYPE_LIKE_BLOG = "LIKE_BLOG";
    public static final String TYPE_COMMENT_BLOG = "COMMENT_BLOG";
    public static final String TYPE_FOLLOW = "FOLLOW";

    private static final int SUMMARY_MAX_LENGTH = 100;

    private Long id;
    /** 收件人：这条消息躺在谁的收件箱里。 */
    private Long userId;
    /** 触发人；没有触发者的系统通知写 0。 */
    private Long fromUserId;
    private String type;
    /** 跳转目标：笔记 id；FOLLOW 类型下是被关注者的 user id。 */
    private Long targetId;
    private String summary;
    /** 列名是 is_read；字段跟着 MyBatis 的下划线转驼峰走，叫 isRead 才能自动映射上。 */
    private Integer isRead;
    private LocalDateTime createTime;

    /** 触发人信息是联表带出来的非列字段，和 Blog/Review 一个套路，避免逐条查用户。 */
    private String fromName;
    private String fromIcon;

    /** 列表里那句中文动作描述。未知类型不抛错，回"通知了你"，读收件箱不该因为脏数据白屏。 */
    public String typeText() {
        if (type == null) return "通知了你";
        return switch (type) {
            case TYPE_LIKE_BLOG -> "赞了你的笔记";
            case TYPE_COMMENT_BLOG -> "评论了你的笔记";
            case TYPE_FOLLOW -> "关注了你";
            default -> "通知了你";
        };
    }

    /** 评论摘要进消息表前先截断：列是 VARCHAR(255)，而评论允许 1000 字。 */
    public static String truncateSummary(String text) {
        if (text == null) return null;
        String value = text.trim();
        if (value.isEmpty()) return null;
        return value.length() <= SUMMARY_MAX_LENGTH ? value : value.substring(0, SUMMARY_MAX_LENGTH) + "…";
    }
}
