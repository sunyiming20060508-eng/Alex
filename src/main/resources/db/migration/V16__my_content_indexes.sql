-- ============================================================================
-- V16：给「我的评价」「我的评论」这两条个人列表查询补索引（阶段 5.5 批次 A 的 A4）
--
-- 为什么现在才补：这两条 SQL 是阶段 5「我的内容」加出来的，建表时的索引是给
-- 「商家详情按时间翻评价」和「笔记详情按时间翻评论」准备的，没人替"按人翻自己的"想过：
--   review       只有 uk_review_order(order_id) + idx_review_shop(shop_id, id) + idx_review_status(status, id)
--   blog_comment 只有 idx_comment_blog(blog_id)
-- 而 ReviewMapper.pageByUser / BlogCommentMapper.pageByAuthor 的形状都是
--   where user_id = ? order by id desc limit ?, ?
-- → 全表扫 + filesort。数据量小时看不出问题，但"这条 SQL 走什么索引"是面试官必问的一句。
--
-- 索引形状选 (user_id, id)：等值列在前，排序列紧跟其后，倒序翻页可以直接走索引反向扫，
-- 不需要额外排序；(user_id) 单列索引做不到这点。
--
-- 这一条是手工跑的（接 Flyway 之前），**只跑一遍**：MySQL 8 的 ALTER TABLE ADD INDEX 没有 IF NOT EXISTS，
-- 重复执行会报 Duplicate key name，属于预期，不要为了让它可重复而把脚本改成动态 SQL。
-- 它同时是 Flyway 的 baseline-version=16：V1–V16 由存量库"认领"、不重放也不校验，从 V17 起才由 Flyway 自动执行。
-- ============================================================================

alter table review add key idx_review_user (user_id, id) comment '我的评价：按人 + 按时间倒序翻页';

alter table blog_comment add key idx_comment_author (user_id, id) comment '我的评论：按人 + 按时间倒序翻页';
