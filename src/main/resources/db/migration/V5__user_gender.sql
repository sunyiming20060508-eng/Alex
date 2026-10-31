-- 阶段 3 收尾欠账：user 表补 gender 列。
-- 执行方式：由 Flyway 按版本号自动执行，不要再手工 source（存量库 baseline-version=16 已把 V1–V16 记成基线，只补 V17 以后）。
-- 不是幂等脚本，手工重复执行会报列已存在。
--
-- 为什么现在才补：阶段 1 做资料接口时 user 表没有这一列，UserVO.gender 只能恒为 null，
-- PUT /api/user/me 收到 gender 也直接丢弃——接口形状"支持"而实际不支持，是契约上的假象。
-- 这一版把列加上，让 DTO → 实体 → 表 → VO 一条线真的闭合。

ALTER TABLE user
    ADD COLUMN gender TINYINT NOT NULL DEFAULT 0 COMMENT '性别：0 保密（默认）、1 男、2 女；取值口径同黑马点评' AFTER icon;
