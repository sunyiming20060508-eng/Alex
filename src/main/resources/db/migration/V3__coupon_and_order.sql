-- 阶段 3 前置：优惠券与订单的表结构。
-- 执行方式：由 Flyway 按版本号自动执行，不要再手工 source（这批写在接入 Flyway 之前，当时是手工跑的）。
-- 脚本不是幂等的：手工重复执行会因为列/表已存在而报错。Flyway 用 flyway_schema_history 保证每个版本只跑一次，
-- 存量库靠 baseline-version=16 认领、不重放这些老脚本。执行前先看第 0 节的现状确认。
-- 前提：coupon 和 orders 当时都是 0 行，所以这里敢直接加 NOT NULL 列和唯一索引；
-- 如果将来表里已经有数据，这些语句要先改成"加可空列 → 回填 → 再收紧"。

-- ---------- 1. coupon：券模板 ----------
-- 只做满减券，所以没有 type 列；有效期用固定的 begin_time/end_time，不做"领取后 N 天有效"那套双语义。
ALTER TABLE coupon
    MODIFY name VARCHAR(100) NOT NULL COMMENT '券名，前端直接展示',
    MODIFY amount DECIMAL(10, 2) NOT NULL COMMENT '减免金额（元）',
    MODIFY threshold_amount DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '使用门槛（元），0 表示无门槛',
    MODIFY stock INT NOT NULL DEFAULT 0 COMMENT '剩余库存；扣减一律用 where id=? and stock>0 的乐观锁，不用等于快照的值',
    MODIFY begin_time DATETIME NOT NULL COMMENT '可领取开始时间',
    MODIFY end_time DATETIME NOT NULL COMMENT '券有效期截止，也是领取时写进 user_coupon.expire_time 的值',
    MODIFY status TINYINT NOT NULL DEFAULT 1 COMMENT '1上架可领 0下架',
    ADD COLUMN create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    ADD COLUMN update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    ADD INDEX idx_coupon_shop_status (shop_id, status),
    ADD INDEX idx_coupon_status_end (status, end_time);

-- ---------- 2. user_coupon：用户已领取的券 ----------
-- 关键设计：面额、门槛、到期时间在领取那一刻**快照**进这张表。
-- 券模板之后被改或下架，都不影响用户手里已经领到的券，也不用再去 join coupon。
CREATE TABLE IF NOT EXISTS user_coupon
(
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id          BIGINT         NOT NULL COMMENT '领取人',
    coupon_id        BIGINT         NOT NULL COMMENT '来源券模板',
    shop_id          BIGINT         NOT NULL COMMENT '冗余：券归属商家，"我的优惠券"按商家分组时不用再 join',
    name             VARCHAR(100)   NOT NULL COMMENT '快照：券名',
    amount           DECIMAL(10, 2) NOT NULL COMMENT '快照：减免金额',
    threshold_amount DECIMAL(10, 2) NOT NULL COMMENT '快照：使用门槛',
    status           TINYINT        NOT NULL DEFAULT 1 COMMENT '1未使用 2已使用 3已过期',
    receive_time     DATETIME                DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
    expire_time      DATETIME       NOT NULL COMMENT '快照自 coupon.end_time',
    use_time         DATETIME                DEFAULT NULL COMMENT '核销时间',
    order_id         BIGINT                  DEFAULT NULL COMMENT '核销它的订单 id',
    -- 一人一券的数据库兜底：Redis 判重挡并发，这条索引挡"Redis 挂了/脚本重放"
    UNIQUE KEY uk_coupon_user (coupon_id, user_id),
    KEY idx_user_status (user_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户优惠券';

-- ---------- 3. orders：订单主表 ----------
-- 状态机只有 4 个值，刻意不照抄苍穹外卖的 6 个（待接单/已接单/派送中是外卖配送链路，到店消费用不上）。
-- 所有状态变更一律走"守卫式 UPDATE"：where id=? and user_id=? and status in (...)，
-- 影响行数为 0 就当失败，校验和写入合成一步。
ALTER TABLE orders
    ADD COLUMN number VARCHAR(50) NOT NULL COMMENT '订单号，对外展示与幂等用' AFTER id,
    ADD COLUMN consignee VARCHAR(50) DEFAULT NULL COMMENT '联系人姓名快照' AFTER total_amount,
    ADD COLUMN phone VARCHAR(20) DEFAULT NULL COMMENT '联系人手机快照' AFTER consignee,
    ADD COLUMN address VARCHAR(255) DEFAULT NULL COMMENT '到店地址或配送地址快照' AFTER phone,
    ADD COLUMN remark VARCHAR(255) DEFAULT NULL COMMENT '用户备注' AFTER address,
    ADD COLUMN user_coupon_id BIGINT DEFAULT NULL COMMENT '本单核销的用户券，NULL 表示没用券' AFTER remark,
    ADD COLUMN discount_amount DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '券抵扣金额；金额是快照，不能每次读的时候重算' AFTER user_coupon_id,
    ADD COLUMN pay_amount DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '实付 = total_amount - discount_amount' AFTER discount_amount,
    ADD COLUMN pay_status TINYINT NOT NULL DEFAULT 0 COMMENT '0未支付 1已支付 2已退款' AFTER pay_amount,
    ADD COLUMN pay_method VARCHAR(20) DEFAULT NULL COMMENT '支付方式；当前只有 MOCK 支付' AFTER pay_status,
    ADD COLUMN cancel_reason VARCHAR(255) DEFAULT NULL COMMENT '取消原因，超时取消会写固定文案' AFTER pay_method,
    ADD COLUMN cancel_time DATETIME DEFAULT NULL COMMENT '取消时间' AFTER cancel_reason,
    ADD COLUMN complete_time DATETIME DEFAULT NULL COMMENT '完成/核销时间' AFTER cancel_time,
    ADD COLUMN update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间' AFTER complete_time,
    MODIFY total_amount DECIMAL(10, 2) NOT NULL COMMENT '订单原价合计',
    MODIFY status TINYINT NOT NULL DEFAULT 1 COMMENT '1待支付 2待使用 3已完成 4已取消',
    MODIFY create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间；超时取消按这一列判断',
    ADD UNIQUE KEY uk_orders_number (number),
    ADD INDEX idx_orders_user_status (user_id, status),
    -- 超时取消那条批量 UPDATE 是 where status=1 and create_time<?，没有这个索引就会全表扫
    ADD INDEX idx_orders_status_create (status, create_time);

-- ---------- 4. order_detail：订单明细 ----------
-- 一张订单可以含多个项目，明细单独成表；下单时用 <foreach> 一次批量插入，不循环单条。
CREATE TABLE IF NOT EXISTS order_detail
(
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id    BIGINT         NOT NULL COMMENT '所属订单',
    shop_id     BIGINT         NOT NULL COMMENT '冗余：明细归属商家',
    goods_name  VARCHAR(100)   NOT NULL COMMENT '项目名快照，商家改名不影响历史订单',
    goods_cover VARCHAR(500) DEFAULT NULL COMMENT '项目图，现阶段可以是表情占位',
    quantity    INT            NOT NULL DEFAULT 1 COMMENT '数量',
    unit_price  DECIMAL(10, 2) NOT NULL COMMENT '单价快照',
    subtotal    DECIMAL(10, 2) NOT NULL COMMENT '小计 = unit_price * quantity',
    create_time DATETIME                DEFAULT CURRENT_TIMESTAMP,
    KEY idx_detail_order (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='订单明细';

-- ---------- 5. 券种子数据 ----------
-- 商家名和门槛对齐 shop 表现有 8 条数据（人均 88/42/128/96/68/35/79/59），
-- 有两条故意设成 stock=0 和 status=0，用来验"已抢光"和"下架不可领"两种前端状态。
INSERT INTO coupon (shop_id, name, amount, threshold_amount, stock, begin_time, end_time, status)
VALUES (1, '江南小馆满100减20', 20.00, 100.00, 50, '2026-10-01 00:00:00', '2026-11-30 23:59:59', 1),
       (1, '江南小馆满50减8', 8.00, 50.00, 100, '2026-10-01 00:00:00', '2026-11-30 23:59:59', 1),
       (2, '拾光咖啡满30减6', 6.00, 30.00, 200, '2026-10-01 00:00:00', '2026-11-30 23:59:59', 1),
       (3, '悦己美发满150减30', 30.00, 150.00, 30, '2026-10-01 00:00:00', '2026-11-30 23:59:59', 1),
       (4, '西溪里满120减25', 25.00, 120.00, 40, '2026-10-01 00:00:00', '2026-11-30 23:59:59', 1),
       (5, '云朵瑜伽满200减50', 50.00, 200.00, 20, '2026-10-01 00:00:00', '2026-11-30 23:59:59', 1),
       (6, '花间里烘焙无门槛10元', 10.00, 0.00, 80, '2026-10-01 00:00:00', '2026-11-30 23:59:59', 1),
       (7, '邻家家政满100减20', 20.00, 100.00, 60, '2026-10-01 00:00:00', '2026-11-30 23:59:59', 1),
       (8, '星光影院满80减15', 15.00, 80.00, 0, '2026-10-01 00:00:00', '2026-11-30 23:59:59', 1),
       (2, '拾光咖啡已下架券', 5.00, 20.00, 10, '2026-10-01 00:00:00', '2026-11-30 23:59:59', 0);
