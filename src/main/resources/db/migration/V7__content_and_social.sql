-- 阶段 4：探店内容与社交（笔记、图片、点赞、评论、关注）。
-- 执行方式：由 Flyway 按版本号自动执行，不要再手工 source（存量库 baseline-version=16 已把 V1–V16 记成基线，只补 V17 以后）。
-- 不是幂等脚本，手工重复执行会报表已存在。
--
-- 两处刻意的取舍，面试时要能自己讲清：
-- 1. **点赞只落数据库唯一索引，不引 Redis Set**。黑马点评用 Set 存"谁点过赞"，是为了在热点笔记上
--    把"我点过吗"做成 O(1) 且计数不重复。这里一人一赞由 uk_blog_user 兜底，列表页用一次 IN 查询
--    批量回填 liked，正确性和读放大都已经解决；再叠一层 Redis 只是多一处要和数据库对账的地方。
-- 2. **关注 Feed 用 Redis ZSet 推模式**（发布时写进每个粉丝的收件箱），这是这一阶段真正要练的机制，
--    键丢了能按 follow + blog 重建，所以 Redis 只当加速层，不担正确性。
--
-- blog.like_count / comment_count 是冗余计数：列表页每篇都要显示，用 count(*) join 会把一页
-- 变成好几次聚合扫描。代价是"改计数"必须和写明细在同一个事务里，Service 层保证。

-- ---------- 1. blog：探店笔记 ----------
CREATE TABLE IF NOT EXISTS blog
(
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL COMMENT '作者',
    shop_id       BIGINT       DEFAULT NULL COMMENT '关联商家，纯生活记录可以为空',
    title         VARCHAR(100) NOT NULL COMMENT '标题',
    content       VARCHAR(2000) NOT NULL COMMENT '正文，一期就是纯文本',
    cover         VARCHAR(50)  DEFAULT NULL COMMENT '列表封面，现阶段用表情占位，和 shop.cover 同一套路',
    like_count    INT          NOT NULL DEFAULT 0 COMMENT '冗余计数，同事务内维护',
    comment_count INT          NOT NULL DEFAULT 0 COMMENT '冗余计数，同事务内维护',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 0 下架（一期没有管理端，先留列给审核用）',
    create_time   DATETIME              DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_blog_create (status, create_time),
    KEY idx_blog_user (user_id),
    KEY idx_blog_shop (shop_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '探店笔记';

-- ---------- 2. blog_image：笔记图片 ----------
CREATE TABLE IF NOT EXISTS blog_image
(
    id        BIGINT PRIMARY KEY AUTO_INCREMENT,
    blog_id   BIGINT       NOT NULL,
    url       VARCHAR(500) NOT NULL COMMENT '本平台上传后的相对地址，不接受外部 URL',
    sort      INT          NOT NULL DEFAULT 0 COMMENT '展示顺序，小的在前',
    create_time DATETIME            DEFAULT CURRENT_TIMESTAMP,
    KEY idx_image_blog (blog_id, sort)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '笔记图片';

-- ---------- 3. blog_like：一人一赞 ----------
CREATE TABLE IF NOT EXISTS blog_like
(
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    blog_id     BIGINT   NOT NULL,
    user_id     BIGINT   NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_blog_user (blog_id, user_id) COMMENT '重复点赞由它兜底，取消赞是删这一行',
    KEY idx_like_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '笔记点赞';

-- ---------- 4. blog_comment：评论（一期不做嵌套回复） ----------
CREATE TABLE IF NOT EXISTS blog_comment
(
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    blog_id     BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    content     VARCHAR(255) NOT NULL,
    create_time DATETIME              DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_comment_blog (blog_id, id) COMMENT '按时间倒序翻评论用，id 单调可比 create_time 更细'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '笔记评论';

-- ---------- 5. follow：关注关系 ----------
CREATE TABLE IF NOT EXISTS follow
(
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id        BIGINT   NOT NULL COMMENT '关注者',
    follow_user_id BIGINT   NOT NULL COMMENT '被关注者',
    create_time    DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_follow (user_id, follow_user_id) COMMENT '重复关注由它兜底，取关是删这一行',
    KEY idx_followed (follow_user_id) COMMENT '查"谁关注了我"和发布时找粉丝列表都走这条'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '关注关系';

-- ---------- 6. 演示数据 ----------
-- 库里现在只有用户本人一个账号，内容和社交没有对手戏可演，所以补三个作者。
-- 手机号用 139 段，和真实账号一眼能分开；密码留 NULL，它们只能被展示不能被登录。
INSERT INTO user (id, phone, nick_name, icon, gender)
VALUES (2, '13900000001', '阿岚', '🌿', 1),
       (3, '13900000002', '老周', '☕', 1),
       (4, '13900000003', '小满', '🥐', 2);

INSERT INTO blog (id, user_id, shop_id, title, content, cover, like_count, comment_count, create_time)
VALUES (1, 2, 1, '糖醋排骨还是那个味', '下午两点去的，不用排队。排骨偏甜口，配米饭刚好；西湖醋鱼这次有点腥，不太推荐。', '🍲', 3, 1, DATE_SUB(NOW(), INTERVAL 6 DAY)),
       (2, 3, 2, '拾光的拿铁适合坐一下午', '豆子是中深烘，酸味不冲。周末人多，工作日下午基本包场，插座够多。', '☕', 2, 1, DATE_SUB(NOW(), INTERVAL 5 DAY)),
       (3, 4, 6, '可颂出炉时间要掐着点', '早上七点半第一炉，晚了就只剩原味。杏仁可颂值得多走两步。', '🥐', 1, 0, DATE_SUB(NOW(), INTERVAL 4 DAY)),
       (4, 2, 5, '瑜伽体验课小结', '老师会逐个纠正体式，一节课下来肩颈松了不少。记得自带水杯。', '🧘', 0, 0, DATE_SUB(NOW(), INTERVAL 3 DAY)),
       (5, 3, 4, '本帮菜偏咸，建议减盐', '红烧肉很香但分量小，两个人点三个菜足够。停车走西门更近。', '🥢', 2, 1, DATE_SUB(NOW(), INTERVAL 2 DAY)),
       (6, 4, 8, '私人影院片源比想象的多', '包了两小时，片源可以提前报给前台。沙发床适合躺着看老片。', '🎬', 0, 0, DATE_SUB(NOW(), INTERVAL 1 DAY)),
       (7, 2, 3, '换了个发型，总监手稳', '沟通了半小时才动剪刀，没有硬推销。染后护理建议加钱做。', '💇', 1, 0, DATE_SUB(NOW(), INTERVAL 10 HOUR)),
       (8, 3, 7, '家政阿姨打扫得很细', '厨房油污处理到位，按小时计费。第一次可以约两小时试试。', '🧹', 0, 0, DATE_SUB(NOW(), INTERVAL 5 HOUR));

INSERT INTO blog_image (blog_id, url, sort)
VALUES (1, '🍲', 0), (1, '🐟', 1),
       (2, '☕', 0), (2, '🪟', 1),
       (3, '🥐', 0),
       (5, '🥢', 0), (5, '🍀', 1),
       (7, '💇', 0);

INSERT INTO blog_like (blog_id, user_id)
VALUES (1, 3), (1, 4), (1, 1),
       (2, 2), (2, 4),
       (3, 2),
       (5, 1), (5, 2),
       (7, 4);

INSERT INTO blog_comment (blog_id, user_id, content)
VALUES (1, 3, '醋鱼确实看运气，上次去还挺好'),
       (2, 4, '插座多这点太加分了'),
       (5, 2, '减盐这个建议实用');

-- 关注关系：让"关注 Feed"一开始就有内容可看（阿岚和老周互相关注，小满关注老周）。
INSERT INTO follow (user_id, follow_user_id)
VALUES (2, 3), (3, 2), (4, 3);
