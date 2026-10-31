-- 阶段 3 补丁：团购套餐表 + 订单核销码（团购模式）。
-- 执行方式：由 Flyway 在启动时按版本号自动执行（`spring.flyway.locations: classpath:db/migration`）。
-- 存量库从 baseline-version 16 往下认领、不重放；全新库从 V1 一路跑到最新。
-- 别再手工 `source`：手工跑一遍 + Flyway 再跑一遍，第二次会报表/列已存在，
-- 而 flyway_schema_history 里没有这条记录，checksum 校验反而对不上。
--
-- 为什么现在才加 goods：阶段 3 第一批只做券和订单接口，明细的项目名和单价是接口直接传进来的。
-- 那在真实项目里是**安全缺陷**——客户端可以自己把 88 改成 0.01。有了 goods 表之后，
-- 下单只传 goodsId + 数量，名称、封面、单价一律服务端从库里读，价格不再经过前端。

-- ---------- 1. goods：商家团购套餐 ----------
-- （原来这一节前面有 `DELETE FROM order_detail; DELETE FROM orders;`：为了让新列能写 NOT NULL
--  直接把历史订单清空。那是**用删数据迁就表结构**，企业里属于一票否决——迁移脚本必须假设库里
--  有不能丢的东西。正确顺序是"加可空列 → 回填 → 收紧"，见本文件末尾那三段。）
CREATE TABLE IF NOT EXISTS goods
(
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    shop_id         BIGINT         NOT NULL COMMENT '归属商家',
    name            VARCHAR(100)   NOT NULL COMMENT '套餐名，下单时快照进 order_detail.goods_name',
    cover           VARCHAR(50)    DEFAULT NULL COMMENT '现阶段用表情占位，和 shop.cover 一致',
    price           DECIMAL(10, 2) NOT NULL COMMENT '团购价，下单唯一认可的单价来源',
    original_price  DECIMAL(10, 2) DEFAULT NULL COMMENT '划线原价，只为展示折扣，不参与金额计算',
    description     VARCHAR(255)   DEFAULT NULL COMMENT '一句话说明，前端副标题用',
    stock           INT            NOT NULL DEFAULT 0 COMMENT '剩余可售份数；扣减用 where id=? and stock>=? 的乐观锁',
    status          TINYINT        NOT NULL DEFAULT 1 COMMENT '1在售 0下架',
    sort            INT            NOT NULL DEFAULT 0 COMMENT '同商家内排序，小的在前',
    create_time     DATETIME                DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME                DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_goods_shop_status (shop_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='商家团购套餐';

-- 8 家商家各 2 条，价格贴着 shop.min_price；悦己美发的烫染套餐 stock=0 用来验"已售罄"。
INSERT INTO goods (shop_id, name, cover, price, original_price, description, stock, status, sort)
VALUES (1, '双人杭帮菜套餐', '🍲', 168.00, 216.00, '西湖醋鱼+东坡肉+两素一汤，含 2 位茶位', 40, 1, 1),
       (1, '招牌醋鱼单人餐', '🐟', 88.00, 108.00, '西湖醋鱼一份，配时蔬与米饭', 60, 1, 2),
       (2, '下午茶双人套餐', '☕', 78.00, 98.00, '两杯手冲 + 当日甜品两份', 100, 1, 1),
       (2, '单品豆手冲两杯', '🫘', 56.00, 72.00, '耶加雪菲/曼特宁任选两杯', 80, 1, 2),
       (3, '洗剪吹＋头皮护理', '💇', 128.00, 168.00, '含深层清洁与 15 分钟按摩', 50, 1, 1),
       (3, '烫染套餐（已售罄示例）', '🎨', 398.00, 528.00, '进口药水，含一次护理', 0, 1, 2),
       (4, '3-4 人本帮菜套餐', '🥢', 288.00, 358.00, '红烧肉+油爆虾+两素一汤', 25, 1, 1),
       (4, '双人午市套餐', '🍚', 128.00, 158.00, '工作日 11:00-14:00 可用', 45, 1, 2),
       (5, '团课 10 节卡', '🧘', 680.00, 880.00, '哈他/流瑜伽通用，有效期 90 天', 20, 1, 1),
       (5, '私教体验课', '🤸', 168.00, 260.00, '60 分钟一对一，含体测', 30, 1, 2),
       (6, '8 寸生日蛋糕', '🎂', 158.00, 198.00, '当日现做，需提前 4 小时预约', 15, 1, 1),
       (6, '面包组合装', '🥐', 45.00, 58.00, '可颂+软欧+贝果各一份', 90, 1, 2),
       (7, '深度保洁 4 小时', '🧹', 299.00, 360.00, '两名保洁师，含厨房卫生间重点清洁', 18, 1, 1),
       (7, '日常保洁 2 次卡', '🧽', 158.00, 198.00, '每次 2 小时，30 天内有效', 35, 1, 2),
       (8, '双人观影套餐', '🎬', 118.00, 150.00, '双人厅 2 小时，含爆米花可乐', 50, 1, 1),
       (8, '包场 2 小时', '🍿', 328.00, 420.00, '独立影厅，最多 6 人', 10, 1, 2);

-- ---------- 3. orders：团购券码与有效期（加列 → 回填 → 收紧）----------
-- 核销码是唯一索引，到店出示、商家按码核销；有效期从下单时间往后算（配置 linli.order.valid-days）。
--
-- 三步走而不是直接 ADD COLUMN ... NOT NULL：直接加会在"表里已经有行"时报 1048/1364，
-- 或者更糟——被 sql_mode 放宽时静默填成 0/空串。空串还会撞唯一索引，整个迁移半途失败。
ALTER TABLE orders
    ADD COLUMN verify_code VARCHAR(20) DEFAULT NULL COMMENT '团购核销码，到店出示' AFTER number,
    ADD COLUMN expire_time DATETIME DEFAULT NULL COMMENT '券码有效期，过期不能再核销' AFTER pay_time;

-- 历史单没有码：给一个带前缀的确定值，既能过唯一索引，也让人一眼看出是补的而不是真发的码。
UPDATE orders
SET verify_code = CONCAT('LEGACY', LPAD(id, 10, '0')),
    expire_time = DATE_ADD(COALESCE(pay_time, create_time, NOW()), INTERVAL 30 DAY)
WHERE verify_code IS NULL
   OR expire_time IS NULL;

ALTER TABLE orders
    MODIFY COLUMN verify_code VARCHAR(20) NOT NULL COMMENT '团购核销码，到店出示',
    MODIFY COLUMN expire_time DATETIME NOT NULL COMMENT '券码有效期，过期不能再核销',
    ADD UNIQUE KEY uk_orders_verify_code (verify_code);

-- ---------- 4. order_detail：关联到具体套餐（同样三步）----------
-- 老明细没有来源套餐，回填成 0（"无对应 goods 行"的哨兵值）而不是删掉整张单：
-- 取消/超时回补库存时按 goods_id 去 join goods 表，0 匹配不到任何行，等于自然跳过，
-- 不会因为一条历史明细就把整张订单的归还逻辑带崩。
ALTER TABLE order_detail
    ADD COLUMN goods_id BIGINT DEFAULT NULL COMMENT '来源套餐 id，取消/超时归还库存时按这一列回补' AFTER order_id;

UPDATE order_detail SET goods_id = 0 WHERE goods_id IS NULL;

ALTER TABLE order_detail
    MODIFY COLUMN goods_id BIGINT NOT NULL COMMENT '来源套餐 id，取消/超时归还库存时按这一列回补';
