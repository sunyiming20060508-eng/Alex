-- V10：站内消息表
--
-- 为什么是"事件发生时写一行"（写时生成），而不是读的时候从 blog_like / blog_comment / follow 聚合出来（读时聚合）：
-- 这条功能的核心是**已读/未读**。读时聚合每次算出来的集合都一样，没有任何地方能记住"这个人看到过哪几条"，
-- 未读数就只能靠前端本地记，换设备就错。所以必须有一张表承接已读状态。
--
-- 反过来，读时聚合的便宜（不用写路径、不会漏事件）我们也没全丢掉：消息只是入口，
-- 点进去读的还是笔记详情本身，点赞数、评论数这些权威值仍然从各自的表算，消息表里的 summary 只是文案。
CREATE TABLE IF NOT EXISTS notification
(
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id      BIGINT      NOT NULL COMMENT '接收人：这条消息躺在谁的收件箱里',
    from_user_id BIGINT      NOT NULL COMMENT '触发人；没有触发者的系统通知留 0',
    type         VARCHAR(20) NOT NULL COMMENT 'LIKE_BLOG 赞了笔记 / COMMENT_BLOG 评论了笔记 / FOLLOW 关注了我',
    target_id    BIGINT      DEFAULT NULL COMMENT '跳转目标：笔记 id；FOLLOW 类型下是被关注者的 user id',
    summary      VARCHAR(255) DEFAULT NULL COMMENT '列表副文案，评论类型存评论原文（截断）',
    is_read      TINYINT     NOT NULL DEFAULT 0 COMMENT '0 未读 1 已读。只前进不回退，所以没有"标为未读"接口',
    create_time  DATETIME             DEFAULT CURRENT_TIMESTAMP,
    -- 一个收件箱的读路径只有两条：按 id 倒序翻页、数未读。都先按 user_id 收敛，
    -- 所以只建这一条联合索引。未读数会在 user_id 段内过滤 is_read——一个人的收件箱量级是他的内容被互动
    -- 的次数，现阶段远不到需要再加覆盖索引的程度，等真出现几万条再补 (user_id, is_read, id)。
    KEY idx_notify_user (user_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '站内消息（点赞/评论/关注）';
