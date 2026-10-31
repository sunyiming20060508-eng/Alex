-- 用户端补齐五件事：签到与积分、城市、商家图集、退款、换绑手机号的库结构。
-- 执行方式：由 Flyway 按版本号自动执行，不要再手工 source（存量库 baseline-version=16 已把 V1–V16 记成基线，只补 V17 以后）。
-- 不是幂等脚本，手工重复执行会报表/列已存在。
--
-- 为什么五件事挤在一个迁移里：它们都是"给已有表加一列或加一张小表"，没有互相依赖，
-- 拆成五个 V 反而让"手工 source 五次"变成五倍出错机会（写这一版时还没接 Flyway，每个 V 要人跑一次；现在接了，V17 起自动执行）。
--
-- 三处刻意的取舍：
-- 1. **签到同时落库和 Redis Bitmap**。Bitmap 负责"这个月签了几天、连签几天"这种读得极快的展示，
--    丢了可以按 sign_record 重算；sign_record 负责"一天只能签一次"这条业务约束，靠唯一索引兜。
--    只放 Bitmap 等于把业务真相存在一个可以随时没有的地方，那是缓存不是账本。
-- 2. **积分余额是 user.points 一列，流水是 points_record**。余额可被流水重算（对账时一条 group by 就能查出来
--    对不上），但读列表页不能每次求和，所以两个都留。写这两张表必须在同一个事务里。
-- 3. **退款不新增状态机分支的实现路径**：orders.status 加一个 5（已退款），仍然走
--    OrderMapper.updateStatusWithGuard 那条"带 expect 的 UPDATE"，和支付/取消/核销同一套做法。
--    退款只允许从"待使用"发起——已核销（已完成）的单子钱已经消费掉了，退它就是在骗自己。
--    另外加一列 refund_time，退款时间不能拿 update_time 冒充（任何一次更新都会动它）。
--
-- 换绑手机号不需要新列：user.phone 上本来就有唯一索引，冲突会撞成 DuplicateKeyException，
-- 由服务端翻译成人话。

-- ---------- 1. 城市 ----------
ALTER TABLE shop
    ADD COLUMN city VARCHAR(20) NOT NULL DEFAULT '杭州市' COMMENT '所属城市，首页城市筛选按这一列走' AFTER area;

-- 首页的城市下拉是"库里真有哪些城市"，所以给 (city, status) 建联合索引：
-- 列表查询永远是"某城市 + 在营业"两个条件一起用。
ALTER TABLE shop ADD KEY idx_shop_city (city, status);

-- ---------- 2. 签到与积分 ----------
CREATE TABLE IF NOT EXISTS sign_record
(
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT   NOT NULL,
    date_sign   DATE     NOT NULL COMMENT '签到日期，一天一条',
    create_time DATETIME          DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sign_user_date (user_id, date_sign) COMMENT '一天只能签一次由它兜底，重复签到是 no-op'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '每日签到';

ALTER TABLE user
    ADD COLUMN points INT NOT NULL DEFAULT 0 COMMENT '积分余额，可由 points_record 流水重算' AFTER role;

CREATE TABLE IF NOT EXISTS points_record
(
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    delta       INT          NOT NULL COMMENT '本次变化，正数加分、负数扣分',
    balance     INT          NOT NULL COMMENT '变化后的余额快照，翻流水时不用自己累加',
    type        VARCHAR(20)  NOT NULL COMMENT 'SIGN 签到 / SIGN_MONTH 满勤奖励 / ORDER 消费返积分',
    ref_id      BIGINT                DEFAULT NULL COMMENT '关联单据 id（签到没有）',
    content     VARCHAR(50)           DEFAULT NULL COMMENT '给人看的一句话',
    create_time DATETIME              DEFAULT CURRENT_TIMESTAMP,
    KEY idx_points_user (user_id, id) COMMENT '流水按时间倒序翻，id 单调比 create_time 更细'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '积分流水';

-- ---------- 3. 商家图集 ----------
CREATE TABLE IF NOT EXISTS shop_image
(
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    shop_id     BIGINT       NOT NULL,
    url         VARCHAR(500) NOT NULL COMMENT '本平台上传后的相对地址，或一期用表情占位（和 shop.cover 同一套路）',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '展示顺序，小的在前',
    create_time DATETIME              DEFAULT CURRENT_TIMESTAMP,
    KEY idx_shop_image (shop_id, sort)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '商家图集';

-- ---------- 4. 退款 ----------
ALTER TABLE orders
    ADD COLUMN refund_time DATETIME NULL DEFAULT NULL COMMENT '退款时间；不能用 update_time 冒充，任何更新都会动它';

-- orders.status 多一个取值 5=已退款（列类型本来就是 TINYINT，不需要改表结构，
-- 但注释要跟着改，否则下一个人看注释会以为只有 1~4）
ALTER TABLE orders
    MODIFY COLUMN status TINYINT NOT NULL DEFAULT 1 COMMENT '1 待支付 2 待使用 3 已完成 4 已取消 5 已退款';

-- ---------- 5. 演示数据 ----------
-- 现有 8 家店都在杭州，先把城市填上；再补两家外地店，让"切换城市"这件事在界面上真的看得出区别。
UPDATE shop SET city = '杭州市' WHERE city IS NULL OR city = '';

INSERT INTO shop (id, name, category_id, area, city, score, min_price, distance, tag, cover, address,
                  business_hours, status, distance_meters)
VALUES (9, '巷口面馆·平江路店', 4, '平江路', '苏州市', 4.4, 28, '1.2km', '苏式汤面', '🍜',
        '苏州市姑苏区平江路 66 号', '10:00-21:00', 1, 1200),
       (10, '山塘咖啡', 2, '山塘街', '苏州市', 4.6, 42, '2.8km', '手冲', '☕',
        '苏州市姑苏区山塘街 128 号', '09:00-20:00', 1, 2800)
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO shop_image (shop_id, url, sort)
VALUES (1, '🍲', 0), (1, '🐟', 1), (1, '🥢', 2),
       (2, '☕', 0), (2, '🪟', 1),
       (3, '💇', 0), (3, '🪞', 1),
       (4, '🥘', 0), (4, '🍶', 1),
       (5, '🧘', 0), (5, '🤸', 1),
       (6, '🥐', 0), (6, '🍞', 1), (6, '🎂', 2),
       (7, '🧹', 0), (7, '🧽', 1),
       (8, '🎬', 0), (8, '🍿', 1),
       (9, '🍜', 0), (9, '🥚', 1),
       (10, '☕', 0), (10, '🍰', 1);

-- 签到和积分不塞演示数据：它们是"用户自己攒出来的东西"，造出来的假流水会让签到按钮一点就穿帮。
