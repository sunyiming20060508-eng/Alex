-- 阶段 5 第三批：个人中心的收藏与收货地址。
-- 执行方式：由 Flyway 按版本号自动执行，不要再手工 source（存量库 baseline-version=16 已把 V1–V16 记成基线，只补 V17 以后）。
-- 不是幂等脚本，手工重复执行会报表已存在。
--
-- 这一批只建两张表，第三件事（浏览记录）**故意不建表**，三个决定都要能自己讲清：
-- 1. **收藏落数据库唯一索引，不学黑马点评那样只放 Redis Set**。收藏是用户会跨设备、跨月回看的东西，
--    Redis 丢了就是"我明明收藏过"，属于数据丢失，不能只当加速层。一人一店由 uk_favorite_user_shop
--    兜底（重复点收藏是 no-op，取消是删行），"这家我收藏了吗"和笔记的 liked 一样用一次 IN 查询批量回填，
--    不给列表页加 N+1。
-- 2. **地址是下单时的快照来源，不是订单的外键**。orders 里已经有 consignee/phone/address 三列，
--    下单时把当时的地址抄进去；之后用户改地址、删地址都不影响历史订单显示。所以这里可以硬删，
--    也不用担心"删了地址历史订单就残缺"。
-- 3. **浏览记录用 Redis ZSet（score=秒级时间戳，member=shopId），不进 MySQL**。它天然是"最近看过"的
--    短期便利，30 天后没意义，丢了也不丢业务数据——和关注 Feed 那个 ZSet 同一个定位。反过来，
--    真要做"我的足迹"永久列表再建表不迟，现在建表等于给自己加一张需要清理的冷数据表。

-- ---------- 1. user_favorite：商家收藏 ----------
CREATE TABLE IF NOT EXISTS user_favorite
(
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT   NOT NULL COMMENT '收藏人',
    shop_id     BIGINT   NOT NULL COMMENT '被收藏的商家',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_favorite_user_shop (user_id, shop_id) COMMENT '一人一店一条；取消收藏是删这一行',
    KEY idx_favorite_user (user_id, id) COMMENT '"我的收藏"按收藏时间倒序翻页'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '商家收藏';

-- ---------- 2. user_address：收货地址 ----------
-- 手机号列宽和 user.phone 保持一致（VARCHAR(20)），地址文本给 255：orders.address 也是 255，
-- 快照过去不会截断——两边不一样长是"下单报数据过长"这类 bug 的常见来源。
CREATE TABLE IF NOT EXISTS user_address
(
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL COMMENT '归属用户，所有读写都按它收口',
    consignee   VARCHAR(20)  NOT NULL COMMENT '收货人',
    phone       VARCHAR(20)  NOT NULL COMMENT '联系电话',
    address     VARCHAR(255) NOT NULL COMMENT '详细地址，下单时快照进 orders.address',
    tag         VARCHAR(10)  DEFAULT NULL COMMENT '家/公司/学校，纯展示用',
    is_default  TINYINT      NOT NULL DEFAULT 0 COMMENT '1 默认地址，每人至多一条，由 Service 在同一事务里保证',
    create_time DATETIME              DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_address_user (user_id, is_default, id) COMMENT '列表就是 order by is_default desc, id desc：默认在最前、其余按新增倒序，一条索引走完'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '收货地址';
