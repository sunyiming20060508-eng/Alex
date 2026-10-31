-- V9：评价要能挂到订单上
--
-- review 表是 V1 按苍穹外卖的建表脚本抄下来的（id/user_id/shop_id/content/score/images/create_time），
-- 一直没接口在用。真要做"下单 → 支付 → 核销 → 评价"这条闭环，缺的正是**评的是哪一单**：
-- 没有 order_id 就没法判断"这单评过了没有"，也没法把评价资格限制在已完成订单上。
--
-- 这一版顺手把两列收紧：表和索引建了半年但一行数据都没有，现在改不需要回填，成本为零。
ALTER TABLE review
    ADD COLUMN order_id BIGINT DEFAULT NULL COMMENT '评的是哪一单；一人一单一条，由 uk_review_order 兜底' AFTER shop_id,
    MODIFY COLUMN score TINYINT NOT NULL COMMENT '1-5 星，必填',
    MODIFY COLUMN content VARCHAR(1000) NOT NULL COMMENT '文字评价，必填（和 DTO 的 @NotBlank 对齐）',
    ADD UNIQUE KEY uk_review_order (order_id) COMMENT '重复评价由它拦，Service 把它翻译成 40901',
    ADD KEY idx_review_shop (shop_id, id) COMMENT '商家详情按时间倒序翻评价';
