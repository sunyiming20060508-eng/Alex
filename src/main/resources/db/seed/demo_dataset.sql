-- ============================================================
-- 邻里生活 · 演示数据集（可重复执行）
--
-- 执行：
--   mysql -uroot --default-character-set=utf8mb4 life_service -e "source D:/Test/test/my/backend/src/main/resources/db/seed/demo_dataset.sql"
--
-- 三条规矩，改这个文件前先读：
--
-- 1. **演示行全部落在 9000-9999 这一段**。脚本每次先删这段再插，所以能反复跑；
--    真实业务产生的行自动增量从 10000 往后走，**永远不会被这个脚本删到**——
--    包括用户本人在浏览器里点出来的订单和笔记。
--    所以清场那一段**只按各表自己的主键段筛**，一条都不带 blog_id / user_id / follow_user_id
--    这种业务列条件（2026-10-28 之前不是这样，那次实测把三条真实探针行删没了，见 WORKLOG S-136）。
--    配套规矩：脚本替真实账号（1/2/3/4）插的任何行都要带 `not exists` 守卫，
--    否则同一对被他自己点出来过，重灌就会撞唯一索引、整个事务回滚。
-- 2. **金额和计数不写死**。订单先只插"哪几样套餐各几个"，单价、快照字段、原价、抵扣、实付
--    全部由结尾的对账 UPDATE 从 `goods` 和 `user_coupon` 算出来，规则和 OrderServiceImpl 一致
--    （抵扣 = min(券面额, 原价)，实付 = 原价 - 抵扣）。数据自洽是脚本保证的，不是肉眼核对的。
-- 3. **封面和图片继续用表情占位，不放上传地址**。`backend/uploads/` 里没有对应的文件，
--    放上去前端就是一排破图。造的数据必须是代码真能产出的形状，这条也是 V8 之后最容易犯的错。
--
-- 没灌 `review` 表：它没有任何读写接口（阶段 5 才做），造了也没地方显示，只会在核对时浪费眼神。
-- ============================================================

-- ---------- 0. 清场（只清 9xxx 这一段） ----------
-- 整个数据段（0 到 10.4）在一个事务里：这个脚本要在别人机器上"照着 README 跑一遍就能演示"，
-- 中途报错留下半套数据（订单有了、明细没有 / 券包翻了一半）比直接不跑更糟。
-- 第 10.5 节那些 `alter table ... auto_increment` 是 DDL、MySQL 会隐式提交，所以事务在它们之前先 commit。
-- 用 `mysql -e "source 本文件"` 或客户端整文件执行都行；单独挑几段执行会破坏这里的自洽前提。
start transaction;

-- 先解绑再删单：真实段（id ≥ 10000）的券如果挂着演示订单，删掉订单它就变成"已使用但没有订单"的孤儿行，
-- 下一次重灌还会在 uk_coupon_user 那一层多一个解释不清的状态。按服务端 releaseByOrderIds 的口径还原成"未使用"。
update user_coupon uc
    join orders o on o.user_coupon_id = uc.id
set uc.status = 1, uc.use_time = null, uc.order_id = null
where o.id between 9000 and 9999 and uc.id not between 9000 and 9999;

delete from order_detail where order_id between 9000 and 9999;
delete from orders where id between 9000 and 9999;
delete from user_coupon where id between 9000 and 9999;
delete from review where id between 9000 and 9999;

-- 清场**只按各表自己的主键段**筛，一条都不许带 blog_id / user_id / follow_user_id 这种"业务列"条件。
-- 原来这里写的是 `blog_id between 9000 and 9999 or user_id between 9000 and 9999`（点赞、评论）
-- 和 `... or follow_user_id between 9000 and 9999`（关注），于是**真实用户落在演示内容上的互动会被删掉**：
-- 他给演示笔记点的赞、评的论、关注的演示号，一次重灌全没了，直接违反文件开头第 1 条承诺。
-- 2026-10-28 在沙箱库里实测过：三条真实段（id≥10000）的探针行跑完原件脚本全部归零（WORKLOG S-136）。
-- 只按 id 筛就足够覆盖演示行——本脚本插的每一行都自带 9xxx 显式主键，而 10.5 节把所有表的
-- auto_increment 顶到 10000，真实行永远落在段外。
-- 顺带一个必须一起改的点：脚本替真实用户（1/2/3/4）往演示笔记上插赞，那些 (blog_id,user_id) 对
-- 有可能他已经自己点出来了，所以第 8 节的两条 blog_like 插入都加了 not exists 守卫，
-- 否则重灌会在 uk_blog_user 上撞 1062、整个事务回滚（那比少几行演示赞严重得多）。
delete from blog_comment where id between 9000 and 9999;
delete from blog_like where id between 9000 and 9999;
delete from blog_image where id between 9000 and 9999;
delete from blog where id between 9000 and 9999;

delete from follow where id between 9000 and 9999;
delete from notification where id between 9000 and 9999;
delete from user_favorite where id between 9000 and 9999;
delete from user_address where id between 9000 and 9999;
delete from goods where id between 9000 and 9999;
delete from coupon where id between 9000 and 9999;
delete from user where id between 9000 and 9999;

-- ---------- 1. 用户：10 个演示账号 ----------
-- password 一律留 NULL，所以它们**只能被展示、不能登录**（密码登录那条分支会直接拒），
-- 不会出现"演示号被人登进去"的口子。手机号用 139 段，和用户真实号码一眼分开。
insert into user (id, phone, nick_name, icon, gender, create_time) values
       (9100, '13900009100', '阿May', '🍵', 2, '2026-08-11 19:22:03'),
       (9101, '13900009101', '老陈', '☕', 1, '2026-08-19 09:41:27'),
       (9102, '13900009102', '小徐', '🥐', 2, '2026-08-26 14:05:12'),
       (9103, '13900009103', '王师傅', '🧹', 1, '2026-09-02 08:30:55'),
       (9104, '13900009104', '卷卷', '💇', 2, '2026-09-09 21:14:36'),
       (9105, '13900009105', '大熊', '🎬', 1, '2026-09-15 20:02:41'),
       (9106, '13900009106', '静静', '🧘', 2, '2026-09-23 07:55:18'),
       (9107, '13900009107', '阿凯', '🥢', 1, '2026-10-01 12:33:09'),
       (9108, '13900009108', '桃子', '🍰', 2, '2026-10-07 16:48:22'),
       (9109, '13900009109', '阿舟', '🍜', 1, '2026-10-14 11:20:47');

-- ---------- 2. 优惠券：4 张新的，其中两张已经到期 ----------
-- 9200-9202 补上"美发满 200 减 45""影院满 200 减 40""本帮菜满 300 减 60"，
-- 让高价套餐（团课卡、包场）也能演示用券；
-- 9203/9204 的 end_time 已经过了：券列表里仍在（status=1），领取时会命中
-- "这张券已过期"那条分支，同时是 user_coupon 里"已过期"两种显示形态的数据来源。
insert into coupon (id, shop_id, name, amount, threshold_amount, stock, begin_time, end_time, status) values
       (9200, 3, '悦己美发满200减45', 45.00, 200.00, 40, '2026-10-01 00:00:00', '2026-11-30 23:59:59', 1),
       (9201, 8, '星光影院满200减40', 40.00, 200.00, 25, '2026-10-01 00:00:00', '2026-11-30 23:59:59', 1),
       (9202, 4, '西溪里满300减60', 60.00, 300.00, 15, '2026-10-01 00:00:00', '2026-11-30 23:59:59', 1),
       (9203, 1, '江南小馆满80减15（已到期）', 15.00, 80.00, 30, '2026-09-01 00:00:00', '2026-10-20 23:59:59', 1),
       (9204, 6, '花间里烘焙满150减30（已到期）', 30.00, 150.00, 20, '2026-09-01 00:00:00', '2026-10-15 23:59:59', 1);

-- ---------- 3. 团购套餐：给六家店各补第三个 ----------
-- 原先每家恰好两个套餐，整齐得像测试数据；补完之后每家 2-3 个，sort 顺序也拉开了。
-- 6 号店的 9305 故意留低库存，用来演示"只剩几份"和下单失败。
insert into goods (id, shop_id, name, cover, price, original_price, description, stock, status, sort) values
       (9300, 1, '店主推荐·醋鱼＋点心双人餐', '🐟', 128.00, 168.00, '招牌醋鱼配两份时令点心，两个人吃刚好。', 40, 1, 3),
       (9301, 2, '手冲＋可颂单人早午餐', '🥐', 49.00, 68.00, '工作日 10 点前可用，可换成燕麦奶。', 60, 1, 3),
       (9302, 3, '儿童剪发', '✂️', 68.00, 88.00, '12 岁以下，含洗头，约二十分钟。', 50, 1, 3),
       (9303, 5, '空中瑜伽 4 节卡', '🧘', 520.00, 680.00, '倒挂系列，需先上一节基础课。', 12, 1, 3),
       (9304, 7, '开荒保洁（90㎡ 以内）', '🧽', 498.00, 660.00, '两人四小时，自带清洁剂与工具。', 8, 1, 3),
       (9305, 8, '主题包场 3 小时', '🎬', 588.00, 760.00, '含片单定制，可自带投影。', 5, 1, 3);

-- ---------- 4. 用户券包 ----------
-- 快照列（shop_id/name/amount/threshold_amount/expire_time）不手抄，直接从 coupon 选出来，
-- 和 CouponServiceImpl 领取那一刻的赋值顺序一模一样；status 一律先给"未使用"，
-- 用掉的那几张由 7.2 按订单翻成"已使用"。
-- 9203/9204 两张券本身已过 end_time，所以它们生成的券包天然就是"已过期"，不用另外造时间。
insert into user_coupon (id, user_id, coupon_id, shop_id, name, amount, threshold_amount, status, receive_time, expire_time)
select v.id, v.user_id, v.coupon_id, cp.shop_id, cp.name, cp.amount, cp.threshold_amount, 1, v.receive_time, cp.end_time
from (select 9400 as id, 1 as user_id, 1 as coupon_id, '2026-10-15 10:12:31' as receive_time
      union all select 9401, 1, 8, '2026-10-08 14:55:02'
      union all select 9402, 1, 2, '2026-10-20 09:31:44'
      union all select 9403, 1, 7, '2026-10-22 18:07:12'
      union all select 9404, 1, 9203, '2026-09-28 11:02:51'
      union all select 9405, 1, 9204, '2026-09-20 20:14:03'
      union all select 9406, 9100, 2, '2026-10-19 12:02:11'
      union all select 9407, 9102, 7, '2026-10-05 17:41:29'
      union all select 9408, 9105, 9, '2026-10-11 21:03:57'
      union all select 9409, 9107, 5, '2026-10-16 11:28:39'
      union all select 9410, 9101, 3, '2026-10-21 08:50:14'
      union all select 9411, 9104, 4, '2026-10-13 15:36:22'
      union all select 9412, 9106, 6, '2026-10-18 10:09:48'
      union all select 9413, 9103, 8, '2026-10-23 19:22:05'
      union all select 9414, 9108, 9204, '2026-09-25 13:44:31'
      union all select 9415, 9109, 9203, '2026-10-02 18:16:59'
      union all select 9416, 9100, 3, '2026-10-24 09:12:06'
      union all select 9417, 9105, 9201, '2026-10-24 20:31:40'
      union all select 9418, 9107, 9202, '2026-10-25 10:05:19'
      union all select 9419, 9102, 1, '2026-10-25 11:47:33') v
         join coupon cp on cp.id = v.coupon_id
-- uk_coupon_user 是 (coupon_id, user_id) 唯一索引，本脚本只删 9xxx 段的券包行。
-- 只要他自己在界面上领过 9400/9401/9402… 里任何一张（真实行落在 10000+，删不到），
-- 这里不加守卫就会在重灌的第 4 节撞 1062 中断——前面的清场和 1-3 节已经执行完，库变成半套。
-- not exists 的语义是"这张券他确实已经有了，演示行就不再重复造"，第 5.5 节负责把订单指到他那一行。
where not exists (select 1
                  from user_coupon x
                  where x.coupon_id = v.coupon_id
                    and x.user_id = v.user_id);

-- ---------- 5. 订单 ----------
-- 只写"订单头"，金额三兄弟（total/discount/pay）留 0，由第 7 节的对账算出来。
-- 状态机四态都齐：1 待支付 / 2 待使用 / 3 已完成 / 4 已取消；
-- 已支付的一律 pay_method='MOCK'（现在没有真支付渠道，代码写的就是这个值）。
--
-- ⚠️ 9004 是待支付，`linli.order.pay-timeout-minutes` 默认 5 分钟，
--    OrderTask 每分钟扫一次，所以**它会在灌完后不到 5 分钟内自动变成已取消**，
--    取消原因就是 Order.TIMEOUT_CANCEL_REASON 那句固定文案。
--    这不是数据坏了，这条 cron 本来就该被演示到；想留一条待支付截图，灌完立刻拍，
--    或者在浏览器里下一单再拍。
insert into orders (id, number, verify_code, user_id, shop_id, total_amount, consignee, phone, address, remark,
                    user_coupon_id, discount_amount, pay_amount, pay_status, pay_method,
                    cancel_reason, cancel_time, complete_time, status, create_time, pay_time, expire_time) values
       -- 用户本人（id=1）五单，覆盖四种状态
       (9001, 'L202610181231050147', '2048153690', 1, 1, 0, '邻里用户', '16639319507', '西湖区文三路 138 号', '需要提前一天订位',
        9400, 0, 0, 0, null, null, null, null, 2, '2026-10-18 12:31:05', '2026-10-18 12:33:41', '2026-11-17 12:31:05'),
       (9002, 'L202610120914120382', '3175028846', 1, 5, 0, '邻里用户', '16639319507', '拱墅区上塘路 55 号', null,
        null, 0, 0, 0, null, null, null, null, 3, '2026-10-12 09:14:12', '2026-10-12 09:15:03', '2026-11-11 09:14:12'),
       (9003, 'L202610212040330915', '5820914377', 1, 6, 0, '邻里用户', '16639319507', '西湖区文三路 138 号', '下午三点来取',
        null, 0, 0, 0, null, null, null, null, 4, '2026-10-21 20:40:33', null, '2026-11-20 20:40:33'),
       (9004, concat('L', date_format(now(), '%Y%m%d%H%i%s'), '0260'), '7301849562', 1, 2, 0, '邻里用户', '16639319507', '西湖区文三路 138 号', null,
        null, 0, 0, 0, null, null, null, null, 1, now(), null, date_add(now(), interval 30 day)),
       (9005, 'L202610081520440538', '9416203578', 1, 7, 0, '邻里用户', '16639319507', '西湖区文三路 138 号', '门铃坏了请打电话',
        9401, 0, 0, 0, null, null, null, null, 3, '2026-10-08 15:20:44', '2026-10-08 15:22:10', '2026-11-07 15:20:44'),
       -- 演示账号： mostly 已完成，掺两单待使用和两单已取消
       (9006, 'L202610191241200741', '1607384925', 9100, 1, 0, '阿May', '13900009100', '滨江区长河街道 12 号', '少放糖',
        9406, 0, 0, 0, null, null, null, null, 2, '2026-10-19 12:41:20', '2026-10-19 12:42:08', '2026-11-18 12:41:20'),
       (9007, 'L202610141833100256', '6482017395', 9101, 3, 0, '老陈', '13900009101', '余杭区五常大道 88 号', null,
        null, 0, 0, 0, null, null, null, null, 3, '2026-10-14 18:33:10', '2026-10-14 18:34:52', '2026-11-13 18:33:10'),
       (9008, 'L202610111602150873', '2095618437', 9102, 6, 0, '小徐', '13900009102', '西湖区古墩路 300 号', '生日蛋糕写"妈妈生日快乐"',
        9407, 0, 0, 0, null, null, null, null, 3, '2026-10-11 16:02:15', '2026-10-11 16:03:27', '2026-11-10 16:02:15'),
       (9009, 'L202610231015070462', '8371029465', 9103, 7, 0, '王师傅', '13900009103', '拱墅区半山路 21 号', null,
        null, 0, 0, 0, null, null, null, null, 2, '2026-10-23 10:15:07', '2026-10-23 10:16:19', '2026-11-22 10:15:07'),
       (9010, 'L202610171944080155', '4752018369', 9104, 3, 0, '卷卷', '13900009104', '西湖区教工路 18 号', null,
        null, 0, 0, 0, null, null, null, null, 3, '2026-10-17 19:44:08', '2026-10-17 19:45:33', '2026-11-16 19:44:08'),
       (9011, 'L202610212018300928', '9183074265', 9105, 8, 0, '大熊', '13900009105', '滨江区江南大道 556 号', '片单自己带',
        9408, 0, 0, 0, null, null, null, null, 2, '2026-10-21 20:18:30', '2026-10-21 20:19:41', '2026-11-20 20:18:30'),
       (9012, 'L202610151100320647', '3847201956', 9106, 5, 0, '静静', '13900009106', '西湖区文二西路 220 号', null,
        null, 0, 0, 0, null, null, null, null, 3, '2026-10-15 11:00:32', '2026-10-15 11:01:26', '2026-11-14 11:00:32'),
       (9013, 'L202610201304150539', '7029148365', 9107, 4, 0, '阿凯', '13900009107', '上城区望江东路 77 号', '靠窗',
        9409, 0, 0, 0, null, null, null, null, 2, '2026-10-20 13:04:15', '2026-10-20 13:05:02', '2026-11-19 13:04:15'),
       (9014, 'L202610130933220718', '5918273064', 9108, 6, 0, '桃子', '13900009108', '拱墅区丽水路 9 号', null,
        null, 0, 0, 0, null, null, null, null, 3, '2026-10-13 09:33:22', '2026-10-13 09:34:11', '2026-11-12 09:33:22'),
       (9015, 'L202610181512440392', '8260391745', 9109, 2, 0, '阿舟', '13900009109', '西湖区曙光路 60 号', null,
        null, 0, 0, 0, null, null, null, null, 4, '2026-10-18 15:12:44', null, '2026-11-17 15:12:44'),
       (9016, 'L202610091825300184', '1403827569', 9100, 1, 0, '阿May', '13900009100', '滨江区长河街道 12 号', null,
        null, 0, 0, 0, null, null, null, null, 3, '2026-10-09 18:25:30', '2026-10-09 18:26:44', '2026-11-08 18:25:30'),
       (9017, 'L202610221436070259', '6750192843', 9101, 2, 0, '老陈', '13900009101', '余杭区五常大道 88 号', '两杯都要热的',
        9410, 0, 0, 0, null, null, null, null, 2, '2026-10-22 14:36:07', '2026-10-22 14:37:19', '2026-11-21 14:36:07'),
       (9018, 'L202610162105440731', '3094817562', 9102, 8, 0, '小徐', '13900009102', '西湖区古墩路 300 号', null,
        null, 0, 0, 0, null, null, null, null, 4, '2026-10-16 21:05:44', null, '2026-11-15 21:05:44'),
       -- 再补 12 单历史已完成：评价必须挂在已完成订单上（uk_review_order），
       -- 没有这些单，评价就只能挂到"用户本人那两单"上，商家详情页会显得全店只有两个人来过。
       (9019, 'L202610051212100348', '1836204957', 9103, 1, 0, '王师傅', '13900009103', '拱墅区半山路 21 号', null,
        null, 0, 0, 0, null, null, null, null, 3, '2026-10-05 12:12:10', '2026-10-05 12:13:02', '2026-11-04 12:12:10'),
       (9020, 'L202610061830220571', '6493018275', 9104, 2, 0, '卷卷', '13900009104', '西湖区教工路 18 号', null,
        null, 0, 0, 0, null, null, null, null, 3, '2026-10-06 18:30:22', '2026-10-06 18:31:15', '2026-11-05 18:30:22'),
       (9021, 'L202610071120330842', '2957103846', 9105, 3, 0, '大熊', '13900009105', '滨江区江南大道 556 号', null,
        null, 0, 0, 0, null, null, null, null, 3, '2026-10-07 11:20:33', '2026-10-07 11:21:40', '2026-11-06 11:20:33'),
       (9022, 'L202610081441200365', '7130294865', 9106, 4, 0, '静静', '13900009106', '西湖区文二西路 220 号', null,
        null, 0, 0, 0, null, null, null, null, 3, '2026-10-08 14:41:20', '2026-10-08 14:42:11', '2026-11-07 14:41:20'),
       (9023, 'L202610091955040219', '4028571936', 9107, 5, 0, '阿凯', '13900009107', '上城区望江东路 77 号', null,
        null, 0, 0, 0, null, null, null, null, 3, '2026-10-09 19:55:04', '2026-10-09 19:56:02', '2026-11-08 19:55:04'),
       (9024, 'L202610101033120684', '8561209347', 9108, 6, 0, '桃子', '13900009108', '拱墅区丽水路 9 号', null,
        null, 0, 0, 0, null, null, null, null, 3, '2026-10-10 10:33:12', '2026-10-10 10:34:20', '2026-11-09 10:33:12'),
       (9025, 'L202610111512440937', '3702948165', 9109, 7, 0, '阿舟', '13900009109', '西湖区曙光路 60 号', null,
        null, 0, 0, 0, null, null, null, null, 3, '2026-10-11 15:12:44', '2026-10-11 15:13:31', '2026-11-10 15:12:44'),
       (9026, 'L202610132041330528', '9284016357', 9100, 8, 0, '阿May', '13900009100', '滨江区长河街道 12 号', null,
        null, 0, 0, 0, null, null, null, null, 3, '2026-10-13 20:41:33', '2026-10-13 20:42:25', '2026-11-12 20:41:33'),
       (9027, 'L202610171325100476', '5619283740', 9101, 6, 0, '老陈', '13900009101', '余杭区五常大道 88 号', null,
        null, 0, 0, 0, null, null, null, null, 3, '2026-10-17 13:25:10', '2026-10-17 13:26:04', '2026-11-16 13:25:10'),
       (9028, 'L202610191712440853', '2085731946', 9102, 4, 0, '小徐', '13900009102', '西湖区古墩路 300 号', null,
        null, 0, 0, 0, null, null, null, null, 3, '2026-10-19 17:12:44', '2026-10-19 17:13:52', '2026-11-18 17:12:44'),
       (9029, 'L202610211130220641', '7346192850', 9104, 1, 0, '卷卷', '13900009104', '西湖区教工路 18 号', null,
        null, 0, 0, 0, null, null, null, null, 3, '2026-10-21 11:30:22', '2026-10-21 11:31:18', '2026-11-20 11:30:22'),
       (9030, 'L202610231845110296', '4619302758', 9106, 3, 0, '静静', '13900009106', '西湖区文二西路 220 号', null,
        null, 0, 0, 0, null, null, null, null, 3, '2026-10-23 18:45:11', '2026-10-23 18:46:03', '2026-11-22 18:45:11');

-- 已支付 → pay_status=1 + pay_method='MOCK'；已完成 → complete_time；
-- 这两类时间线用 UPDATE 补齐，比在 VALUES 里手抄一遍更不容易写反（比如"已取消却有支付时间"）。
update orders set pay_status = 1, pay_method = 'MOCK' where id between 9000 and 9999 and status in (2, 3) and pay_time is not null;
update orders set complete_time = date_add(pay_time, interval 2 day)
where id between 9000 and 9999 and status = 3 and pay_time is not null;
-- 两单已取消：9003 用户主动取消，9015 是 cron 会写的那种超时取消——
-- 文案必须和 Order.TIMEOUT_CANCEL_REASON 一字不差，否则界面上看着像假数据。
update orders set cancel_reason = '临时有事没去，自己取消了', cancel_time = '2026-10-21 21:02:14' where id = 9003;
update orders set cancel_reason = '计划有变，未到店', cancel_time = '2026-10-16 21:40:11' where id = 9018;
update orders set cancel_reason = '超时未支付，系统自动取消', cancel_time = '2026-10-18 15:18:00' where id = 9015;

-- ---------- 5.5 演示券行被 uk_coupon_user 挡下时，把订单的券引用置空 ----------
-- 上面第 4 节用 not exists 跳过的正是"这张券他已经在界面上领过了"的情况。跳过不等于没事：
-- 9001/9005 这两单还写着 user_coupon_id = 9400/9401，指着一个不存在的行。
-- **原来这一节的做法是"按 (user_id, coupon_id) 找回他真实那一行重挂"，那是错的**：
-- 挂上去之后第 7.2 节会把那张真券翻成"已使用"并回填 order_id=演示单——
-- 演示数据把用户自己的一张券吃掉了，而且下一轮重灌的 0 节解绑只是把它再放回去、再吃一次。
-- 2026-10-28 沙箱实测：他那张 status=1 的真券跑完原件脚本变成 status=2 / order_id=9001 / use_time 非空。
-- 现在只清引用：7.1 的对账用 left join + ifnull(c.amount,0)，没有券就抵扣 0、实付等于原价，
-- 金额仍然自洽，只是这一单演示不了"用券"——**演示数据宁缺毋假，更不能拿真数据去补**。
-- （代价说清楚：他若真领过 9400/9401 那两张券，这两单在界面上就是"没用券的订单"。）
update orders
set user_coupon_id = null
where id between 9000 and 9999
  and user_coupon_id is not null
  and not exists (select 1 from user_coupon c where c.id = orders.user_coupon_id);

-- ---------- 6. 订单明细：只写"哪一单买了哪个套餐几个" ----------
-- shop_id / goods_name / goods_cover / unit_price / subtotal 这五个快照列由 SELECT 从 goods + orders 抄出来，
-- 和 OrderServiceImpl 下单那一刻的快照语义一致（明细记的是当时的名字和价格，之后改菜单不影响历史单）。
insert into order_detail (order_id, goods_id, shop_id, goods_name, goods_cover, quantity, unit_price, subtotal, create_time)
select v.order_id, g.id, o.shop_id, g.name, g.cover, v.quantity, g.price, round(g.price * v.quantity, 2), o.create_time
from (select 9001 as order_id, 1 as goods_id, 1 as quantity
      union all select 9001, 2, 1
      union all select 9002, 9, 1
      union all select 9003, 12, 2
      union all select 9004, 3, 1
      union all select 9005, 14, 1
      union all select 9006, 9300, 1
      union all select 9006, 2, 1
      union all select 9007, 5, 1
      union all select 9008, 11, 1
      union all select 9008, 12, 2
      union all select 9009, 13, 1
      union all select 9010, 5, 1
      union all select 9010, 9302, 1
      union all select 9011, 15, 2
      union all select 9012, 10, 1
      union all select 9013, 7, 1
      union all select 9013, 8, 1
      union all select 9014, 12, 3
      union all select 9015, 3, 1
      union all select 9015, 4, 1
      union all select 9016, 1, 1
      union all select 9016, 9300, 1
      union all select 9017, 4, 2
      union all select 9018, 9305, 1
      union all select 9019, 2, 2
      union all select 9020, 3, 1
      union all select 9021, 5, 1
      union all select 9022, 8, 2
      union all select 9023, 10, 1
      union all select 9024, 11, 1
      union all select 9025, 13, 1
      union all select 9026, 15, 1
      union all select 9027, 12, 4
      union all select 9028, 7, 1
      union all select 9029, 9300, 2
      union all select 9030, 9302, 1) v
         join goods g on g.id = v.goods_id
         join orders o on o.id = v.order_id;

-- ---------- 6.5 商家评价 ----------
-- 评价只能挂在**已完成的订单**上（这是评价服务的资格规则），所以 user_id / shop_id 一律从 orders 反查，
-- 不在这里手抄——手抄就有"评价人不是下单人"这种自相矛盾的数据。
-- 时间取"核销之后 1-3 天"，比订单完成时间晚才讲得通。
-- 注意 9002（用户本人的云朵瑜伽那单）**故意不评**：他在订单页上就能看到「去评价」按钮，
-- 点下去走的是真接口，这条闭环得留一手让他自己试。
insert into review (id, user_id, shop_id, order_id, content, score, create_time)
select v.id, o.user_id, o.shop_id, v.order_id, v.content, v.score,
       date_add(o.complete_time, interval v.after_days day)
from (select 9850 as id, 9005 as order_id, 5 as score, 1 as after_days, '阿姨提前十分钟到，工具自带，厨房台面做完没有水痕。' as content
      union all select 9851, 9007, 4, 2, '剪得整齐，就是洗头那家水温有点忽冷忽热。'
      union all select 9852, 9008, 5, 1, '生日蛋糕按图做的，翻糖等了三天，值。'
      union all select 9853, 9010, 5, 3, '预约制很省心，到店没等，孩子也不闹。'
      union all select 9854, 9012, 4, 2, '空中瑜伽比想象累，教练会一个个纠正动作。'
      union all select 9855, 9014, 3, 1, '面包组合装可以，但可颂那天不是刚出炉的，偏硬。'
      union all select 9856, 9016, 5, 2, '醋鱼不腥，排骨偏甜口，两个人吃 96 块很实在。'
      union all select 9857, 9019, 4, 1, '午市人多但翻台快，等位二十分钟能接受。'
      union all select 9858, 9020, 5, 2, '靠墙那排有插座，坐一下午不难受，豆子每周换。'
      union all select 9859, 9021, 4, 1, '烫染没有推销，中途会来问刺激不刺激。'
      union all select 9860, 9022, 5, 3, '包间能坐十人，红烧肉偏甜，长辈喜欢。'
      union all select 9861, 9023, 4, 1, '团课氛围好，就是高峰期垫子有点紧张。'
      union all select 9862, 9024, 5, 2, '面包回烤三分钟比当天买的还软，囤着划算。'
      union all select 9863, 9025, 2, 1, '这次排到的阿姨不太熟练，窗槽没弄干净，下次换一位。'
      union all select 9864, 9026, 5, 3, '片单可以自己报，音响比预期厚，性价比高。'
      union all select 9865, 9027, 4, 1, '组合装分量足，就是希望多两种无糖的。'
      union all select 9866, 9028, 5, 2, '本帮菜午市比晚市划算，菜量差不多。'
      union all select 9867, 9029, 4, 1, '双人套餐点心偏多，饭量小的两个人吃不完。'
      union all select 9868, 9030, 3, 2, '儿童剪发快，但等位区太小，带娃有点挤。') v
         join orders o on o.id = v.order_id;

-- 商家展示分 = 该店**已通过审核**的评价的平均分（一位小数）。**没有评价的店保持种子分不动**，
-- 否则一次空聚合会把 4.9 变成 NULL。这条和 ReviewServiceImpl / AdminReviewServiceImpl 的口径一致：
-- V14 之后被驳回（status=2）的评价不展示、也不参与算分，所以这里必须带 status 条件，
-- 否则灌完数据商家分就等于"把违规内容也算进去了"，和管理端驳回一次后的分数对不上。
update shop s
    join (select shop_id, round(avg(score), 1) as avg_score from review where status = 1 group by shop_id) r
    on r.shop_id = s.id
set s.score = r.avg_score;

-- ---------- 7. 对账：金额、券状态、笔记计数全部由这里算出来 ----------

-- 7.1 订单原价 = 明细合计；抵扣 = min(券面额, 原价)；实付 = 原价 - 抵扣。
--     和 OrderServiceImpl 第 197 行那条规则一致：无门槛券面额大于消费额时只抵到刚好清零。
update orders o
    join (select order_id, sum(subtotal) as total from order_detail where order_id between 9000 and 9999 group by order_id) x
        on x.order_id = o.id
    left join user_coupon c on c.id = o.user_coupon_id
set o.total_amount = round(x.total, 2),
    o.discount_amount = round(least(ifnull(c.amount, 0), x.total), 2),
    o.pay_amount = round(x.total - least(ifnull(c.amount, 0), x.total), 2);

-- 7.2 用掉券的订单，把券翻成"已使用"并回填 use_time/order_id（券只在已支付订单上算用掉）。
--     **只翻 9xxx 段的券行**：这是"演示数据不许动真实数据"那条承诺的落点。
--     原来这里不过滤，靠的是 5.5 节把演示订单挂到真券上、于是必须连真券一起翻——那条已经拆掉了
--     （见 5.5 的实测证据），现在演示订单能引用的只有脚本自己造的 9xxx 券行，
--     这道 id 过滤是**护栏**：将来谁再把引用指到真实段，这条也不会把真券改成已使用。
update user_coupon c
    join orders o on o.user_coupon_id = c.id
set c.status = 2, c.use_time = o.pay_time, c.order_id = o.id
where o.id between 9000 and 9999 and o.pay_status = 1
  and c.id between 9000 and 9999;

-- 7.3 "已过期"留两种形态的样本：
--     ① status 仍是 1 但 expire_time 已过 —— UserCoupon.statusText 会把这种行显示成"已过期"，
--        这个分支本来就是为"翻转状态的定时任务还没写"准备的，9404/9414/9415 走的是它；
--     ② status=3 —— 真被翻转过的那一条（生产上由一个尚未实现的任务写出来），这里给 9405。
update user_coupon set status = 3 where id = 9405;

-- 笔记的冗余计数不在这里算：那时 blog_like / blog_comment 还没插，算出来必然是 0。
-- 统一放到第 10 节（所有社交数据都插完之后）重算一次。

-- ---------- 8. 探店笔记 ----------
-- 21 篇新的（含一篇不关联商家的"纯生活记录"，用来演示 shopName 为空那一支）。
-- 时间跨到 9 月底，推荐流翻页才有东西可翻。
insert into blog (id, user_id, shop_id, title, content, cover, status, create_time) values
       (9500, 2, 1, '糖醋排骨第二刷', '换了晚市去，人比中午少一半，排骨还是那个味。两个人 96 块够了。', '🍲', 1, '2026-10-25 09:12:40'),
       (9501, 9100, 6, '可颂要掐着出炉时间', '四点那一炉最脆，去早了只能等到五点。', '🥐', 1, '2026-10-25 08:40:11'),
       (9502, 3, 5, '空中瑜伽第一次', '倒挂十分钟腰就酸了，教练说一周两次才见效。', '🧘', 1, '2026-10-24 19:05:33'),
       (9503, 9101, 7, '开荒保洁比想象细', '两个人干了四小时，窗槽和灯槽都拆下来洗。', '🧹', 1, '2026-10-24 17:22:08'),
       (9504, 4, 2, '拾光的插座是加分项', '靠墙那排座每个位子都有插座，坐一下午不难受。', '☕', 1, '2026-10-24 14:03:52'),
       (9505, 9102, 8, '包场看了老片', '提前报了片单，音响比预期厚。', '🎬', 1, '2026-10-23 21:47:19'),
       (9506, 2, 3, '总监剪发要预约', '临时去只能等两小时，提前一天约就没等过。', '💇', 1, '2026-10-23 18:30:05'),
       (9507, 9103, 4, '本帮菜午市划算', '双人午市 128，比晚市便宜 40，菜量差不多。', '🥢', 1, '2026-10-22 12:55:41'),
       (9508, 9104, 1, '西湖醋鱼还是看运气', '这次不腥，但偏甜，接受不了的点叫花鸡更稳。', '🐟', 1, '2026-10-22 13:10:27'),
       (9509, 3, 6, '面包组合装适合囤', '冷冻回烤三分钟，比当天买的还软。', '🍞', 1, '2026-10-21 20:18:36'),
       (9510, 9105, 5, '私教体验课不推销', '一节课全程在纠正呼吸，没被按头买卡。', '💪', 1, '2026-10-21 10:02:14'),
       (9511, 4, 7, '阿姨会自己带工具', '清洁剂是自带的，做完厨房台面没留水痕。', '🧽', 1, '2026-10-20 16:44:58'),
       (9512, 9106, 2, '手冲豆子每周换', '这周是埃塞，果酸明显；不喜欢酸可以让咖啡师换哥伦比亚。', '☕', 1, '2026-10-20 09:35:22'),
       (9513, 9107, 3, '儿童剪发快', '二十分钟搞定，有动画片看，孩子不闹。', '✂️', 1, '2026-10-19 15:20:47'),
       (9514, 9100, 8, '双人观影套餐性价比高', '118 含两杯饮料，比单买票加零食便宜。', '🍿', 1, '2026-10-18 21:11:33'),
       (9515, 9108, 4, '八个人的包间够用', '西溪里包间能坐十人，提前两天订。', '🍶', 1, '2026-10-17 19:40:09'),
       (9516, 9109, 1, '叫花鸡要提前预订', '每天限量，去晚了真没有。', '🍗', 1, '2026-10-16 12:02:55'),
       (9517, 9101, 5, '团课卡可以转赠', '教练同意剩余课时转给朋友，这条比价格更重要。', '🧘', 1, '2026-10-14 18:26:12'),
       (9518, 9102, 7, '日常保洁两次卡划算', '158 两次，折下来一次 79，比单约便宜 20。', '🧺', 1, '2026-10-12 11:09:38'),
       (9519, 9103, 6, '生日蛋糕要提前三天', '八寸的图样能做，但翻糖要等三天。', '🎂', 1, '2026-10-10 20:31:26'),
       (9520, 9104, null, '顺路拍了运河的日落', '不用消费也能坐一会儿，傍晚六点最亮。', '🌇', 1, '2026-10-11 18:40:07'),
       -- 一篇已下架的：getOnlineById 只认 status=1，这篇应该在任何列表里都看不到，
       -- 直接访问详情要回 40401「笔记不存在或已下架」。
       (9521, 9105, 8, '这条是被下架的示例', '用来验证列表和详情都会过滤 status=0。', '🚫', 0, '2026-10-09 19:12:03'),
       -- 三篇更早的，让"翻到底"这件事看起来有历史
       (9522, 2, 2, '九月的拿铁还是老配方', '不加糖也有点甜，可能是那批豆子的问题。', '☕', 1, '2026-09-30 15:22:40'),
       (9523, 3, 4, '本帮菜适合带长辈', '红烧肉偏甜，牙口不好的也能吃。', '🍖', 1, '2026-09-26 12:41:18'),
       (9524, 4, 1, '午市比晚市安静', '十二点去还有靠窗位，一点就要等。', '🥢', 1, '2026-09-21 12:18:05'),
       -- 用户本人的一篇：没有它，他的收件箱会是空的（点赞/评论消息的收件人是笔记作者）
       (9525, 1, 2, '第一次写探店：公司楼下的拾光', '下午三点人最少，手冲比拿铁更值得，靠墙座有插座。', '☕', 1, '2026-10-24 15:40:12');

-- 多图笔记（表情占位，理由见文件开头第 3 条）
insert into blog_image (id, blog_id, url, sort) values
       (9600, 9500, '🍲', 0), (9601, 9500, '🥬', 1), (9602, 9500, '🍚', 2),
       (9603, 9502, '🧘', 0), (9604, 9502, '🪵', 1),
       (9605, 9505, '🎬', 0), (9606, 9505, '🍿', 1),
       (9607, 9507, '🥢', 0), (9608, 9507, '🐟', 1),
       (9609, 9510, '💪', 0),
       (9610, 9514, '🍿', 0), (9611, 9514, '🥤', 1), (9612, 9514, '🎬', 2),
       (9613, 9519, '🎂', 0), (9614, 9519, '🕯️', 1),
       (9615, 9520, '🌇', 0), (9616, 9520, '🛶', 1),
       (9617, 9523, '🍖', 0), (9618, 9523, '🥢', 1);

-- 点赞用取模造均匀分布：手写四十多行既难核对又容易点出"每人只赞了自己"这种假相。
-- (用户 id + 笔记 id) % 7 == 0 在 9xxx 段上会得到每篇 1-4 个赞的参差效果。
-- 最后那条 not exists 是新规矩的配套：清场现在只删 9xxx 段的行（真实互动不再被牵连），
-- 于是"他自己已经点过这个赞"会留在那儿，这里再插同一对就撞 uk_blog_user、整个事务回滚。
-- 跳过不等于少数据——他那一条真赞比脚本造的一条更值钱，而且第 10 节的重算会把两种来源一起数进去。
insert into blog_like (id, blog_id, user_id)
select 9700 + row_number() over (order by b.id, u.id), b.id, u.id
from blog b
         join user u on u.id in (1, 2, 3, 4, 9100, 9101, 9102, 9103, 9104, 9105, 9106, 9107, 9108, 9109)
where b.id between 9500 and 9599
  and b.status = 1
  and mod(u.id + b.id, 7) = 0
  and not exists (select 1 from blog_like x where x.blog_id = b.id and x.user_id = u.id);

-- 演示账号也给 V7 那 8 篇老笔记点几个赞，老笔记的 like_count 才会跟着第 10 节的重算一起涨上去
insert into blog_like (id, blog_id, user_id)
select 9780 + row_number() over (order by b.id, u.id), b.id, u.id
from blog b
         join user u on u.id between 9100 and 9199
where b.id between 1 and 8
  and mod(u.id * 3 + b.id, 5) = 0
  and not exists (select 1 from blog_like x where x.blog_id = b.id and x.user_id = u.id);

insert into blog_comment (id, blog_id, user_id, content, create_time) values
       (9800, 9500, 9104, '排骨我也喜欢晚上去，中午人太多。', '2026-10-25 10:02:11'),
       (9801, 9500, 3, '谢谢分享，下次试试叫花鸡。', '2026-10-25 11:20:38'),
       (9802, 9501, 9108, '五点上去的表示真的等过……', '2026-10-25 09:55:04'),
       (9803, 9502, 9106, '我倒挂五分钟就下来了，还得练。', '2026-10-24 20:11:47'),
       (9804, 9503, 9103, '灯槽确实是最容易被漏掉的地方。', '2026-10-24 18:40:29'),
       (9805, 9504, 9109, '这条对上班族太关键了。', '2026-10-24 15:12:06'),
       (9806, 9505, 9105, '片单可以自己报？那我要试试老港片。', '2026-10-23 22:31:15'),
       (9807, 9506, 9100, '预约要打电话还是小程序上就行？', '2026-10-23 19:22:50'),
       (9808, 9507, 9107, '午市性价比高，就是选择少两道。', '2026-10-22 14:01:33'),
       (9809, 9508, 2, '醋鱼看厨师心情这句太真实。', '2026-10-22 15:44:19'),
       (9810, 9509, 9102, '回烤三分钟这个技巧收下了。', '2026-10-21 21:05:52'),
       (9811, 9510, 9106, '不推销这点值得写进标题。', '2026-10-21 11:38:24'),
       (9812, 9512, 9101, '哥伦比亚那批我也喜欢，没那么酸。', '2026-10-20 10:52:41'),
       (9813, 9514, 9105, '两个人 118 确实划算。', '2026-10-18 22:03:07'),
       (9814, 9515, 9108, '包间要最低消费吗？', '2026-10-17 20:55:36'),
       (9815, 9517, 9106, '能转赠这条太重要了，一个人上不动。', '2026-10-14 19:40:12'),
       (9816, 9519, 9102, '翻糖等三天，我上次是提前四天订的。', '2026-10-10 21:33:48'),
       (9817, 9520, 9104, '傍晚六点那边光线确实最好拍。', '2026-10-11 19:55:22'),
       (9818, 9523, 9107, '带长辈这个定位我认同。', '2026-09-26 14:02:31'),
       (9819, 9524, 9100, '一点去真的会等。', '2026-09-21 13:30:16'),
       -- 给本人那篇笔记两条评论，他的消息页才有"未读"可点
       (9820, 9525, 2, '插座那点评测很实在，下次也坐靠墙。', '2026-10-24 16:22:41'),
       (9821, 9525, 9106, '三点去确实不用排队，豆子今天换了吗？', '2026-10-24 18:05:12');

-- ---------- 9. 关注关系 ----------
-- 1→3（老周）是**特意加的**：用户本人原先只关注阿岚，关注 tab 只有三篇；
-- 加上老周之后他的关注流和笔记作者数一起涨起来，打开就有得翻。
-- 改成 select + not exists 的原因和第 8 节点赞一样：清场只删 9xxx 段，他自己关注出来的边会留着，
-- 这里再插同一对就撞 uk_follow、整个重灌回滚。跳过不是少数据——真实那条边本来就在，
-- 而 9.5 节的消息是从 follow 表现生成的，跳过的边不会多出一条对不上的 FOLLOW 消息（检查⑫ 因此仍然自洽）。
insert into follow (id, user_id, follow_user_id, create_time)
select v.id, v.user_id, v.follow_user_id, v.create_time
from (select 9900 as id, 1 as user_id, 3 as follow_user_id, '2026-10-25 18:05:00' as create_time
      union all select 9901, 9100, 2, '2026-10-18 09:12:31'
      union all select 9902, 9100, 3, '2026-10-19 10:41:02'
      union all select 9903, 9101, 4, '2026-10-14 20:03:17'
      union all select 9904, 9102, 2, '2026-10-11 12:55:40'
      union all select 9905, 9102, 9100, '2026-10-22 21:07:33'
      union all select 9906, 9103, 3, '2026-10-24 08:30:12'
      union all select 9907, 9104, 2, '2026-10-22 14:18:55'
      union all select 9908, 9104, 9100, '2026-10-23 19:44:20'
      union all select 9909, 9105, 4, '2026-10-21 22:11:09'
      union all select 9910, 9106, 3, '2026-10-15 07:40:26'
      union all select 9911, 9106, 9102, '2026-10-20 18:22:47'
      union all select 9912, 9107, 2, '2026-10-19 11:35:04'
      union all select 9913, 9108, 4, '2026-10-13 15:50:38'
      union all select 9914, 9109, 2, '2026-10-16 13:07:21'
      union all select 9915, 9109, 9103, '2026-10-18 18:55:13'
      union all select 9916, 9103, 4, '2026-10-25 09:20:44'
      union all select 9917, 9101, 9106, '2026-10-24 20:36:15'
      -- 阿岚关注了用户本人：他打开消息页能看到"有人关注了你"，而不是只有互动通知
      union all select 9918, 2, 1, '2026-10-25 10:12:33') v
where not exists (select 1 from follow x
                  where x.user_id = v.user_id and x.follow_user_id = v.follow_user_id);

-- ---------- 9.5 站内消息：从事件表"重放"出来，不另写一套人肉清单 ----------
-- 消息本来就是点赞/评论/关注这三类事件的副产品。这里直接从 blog_like / blog_comment / follow 生成，
-- 所以"自己赞自己不发消息"这条规则在数据里同样成立（where 作者 <> 触发人），
-- 而且消息数一定和互动数对得上——第 ⑫ 条检查就是在钉这件事。
-- id 基数取 9500：现在是 115 条，留到 9999 还有 ~380 条余量；真超出去会撞到自增段，
-- 那时候要改的是这个基数而不是删数据。
insert into notification (id, user_id, from_user_id, type, target_id, summary, is_read, create_time)
select 9500 + row_number() over (order by n.create_time, n.type, n.from_user_id, n.target_id),
       n.user_id, n.from_user_id, n.type, n.target_id, n.summary, n.is_read, n.create_time
from (
         -- 点赞：收件人是笔记作者
         select b.user_id as user_id, bl.user_id as from_user_id, 'LIKE_BLOG' as type, b.id as target_id,
                null as summary,
                -- 大部分留成已读，让未读数是个位数的真实样子而不是"99+"
                case when mod(bl.user_id + b.id, 4) = 0 then 0 else 1 end as is_read,
                bl.create_time
         from blog_like bl
                  join blog b on b.id = bl.blog_id
         where b.user_id <> bl.user_id
           and b.status = 1

         union all

         -- 评论：摘要就是评论原文（超长由实体截断，这里同样截 100 字）
         select b.user_id, c.user_id, 'COMMENT_BLOG', b.id,
                case when char_length(c.content) > 100
                         then concat(left(c.content, 100), '…')
                     else c.content end,
                case when mod(c.user_id + b.id, 5) = 0 then 0 else 1 end,
                c.create_time
         from blog_comment c
                  join blog b on b.id = c.blog_id
         where b.user_id <> c.user_id
           and b.status = 1

         union all

         -- 关注：target_id 是被关注者（也就是收件人自己），和 FollowServiceImpl.record 传的一致
         select f.follow_user_id, f.user_id, 'FOLLOW', f.follow_user_id, null,
                case when mod(f.user_id, 3) = 0 then 0 else 1 end,
                f.create_time
         from follow f
         where f.user_id <> f.follow_user_id
     ) n;

-- 用户本人最新的 3 条留成未读：顶栏红点得有东西可亮，否则这一屏看不出"未读"长什么样。
-- 取"最新 3 条"而不是随机：更符合"刚离开一会儿回来"的直觉，也保证重跑结果确定。
update notification n
    join (select id from notification where user_id = 1 order by create_time desc limit 3) t on t.id = n.id
set n.is_read = 0;

-- ---------- 9.6 收藏：让「我的收藏」一打开就有东西 ----------
-- 用户本人收藏 3 家（含一家已休息的，用来显示"收藏了但当前休息"），演示号各 2-3 家。
-- shop_id 只用真实存在的 1-8，第 ⑬ 条检查会盯住引用完整性。
insert into user_favorite (id, user_id, shop_id, create_time)
values (9800, 1, 1, date_sub(now(), interval 2 day)),
       (9801, 1, 2, date_sub(now(), interval 5 day)),
       (9802, 1, 6, date_sub(now(), interval 9 day)),
       (9803, 9100, 1, date_sub(now(), interval 1 day)),
       (9804, 9100, 3, date_sub(now(), interval 3 day)),
       (9805, 9101, 2, date_sub(now(), interval 4 day)),
       (9806, 9101, 5, date_sub(now(), interval 6 day)),
       (9807, 9102, 6, date_sub(now(), interval 7 day)),
       (9808, 9103, 4, date_sub(now(), interval 8 day)),
       (9809, 9103, 7, date_sub(now(), interval 11 day)),
       (9810, 9104, 8, date_sub(now(), interval 12 day));

-- ---------- 9.7 收货地址：每人 1-2 条，用户本人那条是默认 ----------
-- 手机号用演示号段，地址文本是编的。默认地址每人至多一条，由第 ⑭ 条检查钉住。
insert into user_address (id, user_id, consignee, phone, address, tag, is_default, create_time)
values (9860, 1, '邻里用户', '16639319507', '杭州市西湖区文三路 100 号 3 单元 501', '家', 1, date_sub(now(), interval 6 day)),
       (9861, 1, '邻里用户', '16639319507', '杭州市滨江区江南大道 233 号 A 座 12 楼', '公司', 0, date_sub(now(), interval 3 day)),
       (9862, 9100, '阿May', '13900009100', '杭州市拱墅区上塘路 88 号', '家', 1, date_sub(now(), interval 5 day)),
       (9863, 9101, '老陈', '13900009101', '杭州市余杭区五常大道 1 号', null, 1, date_sub(now(), interval 4 day)),
       (9864, 9102, '小徐', '13900009102', '杭州市萧山区市心北路 260 号', '家', 1, date_sub(now(), interval 2 day)),
       (9865, 9103, '王老师', '13900009103', '杭州市西湖区玉古路 55 号', '学校', 1, date_sub(now(), interval 1 day));

-- ---------- 10. 收尾：计数与自洽检查（输出必须都是空表） ----------
-- 计数按真实行数重算，**连 V7 那 8 篇老笔记一起对**：不信任任何手抄的数字，跑完脚本它们一定等于表里的行数。
update blog b
    left join (select blog_id, count(*) as c from blog_like group by blog_id) l on l.blog_id = b.id
    left join (select blog_id, count(*) as c from blog_comment group by blog_id) m on m.blog_id = b.id
set b.like_count = ifnull(l.c, 0), b.comment_count = ifnull(m.c, 0);

select '① 金额不自洽的订单（应为空）' as 检查项;
select o.id, o.total_amount, o.discount_amount, o.pay_amount
from orders o
where o.id between 9000 and 9999
  and (round(o.total_amount - o.discount_amount, 2) <> o.pay_amount
    or o.discount_amount > o.total_amount
    or o.pay_amount < 0);

select '② 未达门槛却用了券的订单（应为空）' as 检查项;
select o.id, o.total_amount, c.threshold_amount
from orders o
         join user_coupon c on c.id = o.user_coupon_id
where o.id between 9000 and 9999
  and o.discount_amount > 0
  and o.total_amount < c.threshold_amount;

select '③ 券和订单不同一商家的（应为空）' as 检查项;
select o.id, o.shop_id, c.shop_id
from orders o
         join user_coupon c on c.id = o.user_coupon_id
where o.id between 9000 and 9999 and o.shop_id <> c.shop_id;

select '④ 已取消却标成已使用的券（应为空）' as 检查项;
select c.id, o.id, c.status
from user_coupon c
         join orders o on o.user_coupon_id = c.id
where o.status = 4 and c.status = 2;

select '⑤ 时间线矛盾的订单（应为空）' as 检查项;
select id, status, pay_status, pay_time, complete_time, cancel_time
from orders
where id between 9000 and 9999
  and ((status in (2, 3) and (pay_time is null or pay_status <> 1))
    or (status = 3 and complete_time is null)
    or (status = 1 and pay_status <> 0)
    or (status = 4 and pay_status <> 0)
    or (status <> 4 and cancel_time is not null));

select '⑥ 明细为空或单价对不上的订单（应为空）' as 检查项;
select o.id, o.total_amount
from orders o
where o.id between 9000 and 9999
  and ((select count(*) from order_detail d where d.order_id = o.id) = 0
    or round((select sum(d.subtotal) from order_detail d where d.order_id = o.id), 2) <> o.total_amount);

select '⑦ 计数对不上的笔记（应为空）' as 检查项;
select b.id, b.like_count, ifnull(l.c, 0) as 实际赞, b.comment_count, ifnull(m.c, 0) as 实际评论
from blog b
         left join (select blog_id, count(*) as c from blog_like group by blog_id) l on l.blog_id = b.id
         left join (select blog_id, count(*) as c from blog_comment group by blog_id) m on m.blog_id = b.id
where b.like_count <> ifnull(l.c, 0) or b.comment_count <> ifnull(m.c, 0);

select '⑧ 券包快照和券面对不上的（应为空）' as 检查项;
select c.id
from user_coupon c
         join coupon cp on cp.id = c.coupon_id
where c.id between 9000 and 9999
  and (c.name <> cp.name
    or c.amount <> cp.amount or c.threshold_amount <> cp.threshold_amount
    or c.shop_id <> cp.shop_id or c.expire_time <> cp.end_time);
-- 这条检查原本要写成 `c.name collate utf8mb4_general_ci <> cp.name collate ...`：
-- V1-V3 建的表是 general_ci、V4 之后是 0900_ai_ci，直接比字符串会 ERROR 1267 Illegal mix of collations。
-- V11 已把全库统一到 utf8mb4_0900_ai_ci，显式 collate 是噪音，去掉了。

select '⑨ 商家展示分不等于"已通过评价"均分的（应为空）' as 检查项;
select s.id, s.score, r.avg_score
from shop s
         join (select shop_id, round(avg(score), 1) as avg_score from review where status = 1 group by shop_id) r
              on r.shop_id = s.id
where s.score <> r.avg_score;

select '⑩ 评价挂错了订单的（应为空）' as 检查项;
select v.id, v.order_id
from review v
         join orders o on o.id = v.order_id
where v.id between 9000 and 9999
  and (o.status <> 3 or o.user_id <> v.user_id or o.shop_id <> v.shop_id or v.create_time < o.complete_time);

select '⑬ 收藏指向了不存在的用户或商家（应为空）' as 检查项;
select f.id, f.user_id, f.shop_id
from user_favorite f
         left join user u on u.id = f.user_id
         left join shop s on s.id = f.shop_id
where f.id between 9000 and 9999
  and (u.id is null or s.id is null);

select '⑭ 一个用户有多条默认地址的（应为空）' as 检查项;
-- 注意是"默认条数"多于一，不是"地址条数"多于一——用户本来就可以有 5 条地址
select user_id, count(*) as 默认条数
from user_address
where is_default = 1
group by user_id
having count(*) > 1;

-- ---------- 10.45 数据段到此为止，提交 ----------
-- 从第 0 节 start transaction 到这里是一个整体：中间任何一条报错，前面所有 delete/insert/update 一起回滚，
-- 库回到执行前的样子，不会出现"清了场但没灌上"的半套数据。
-- 下面的 alter table 是 DDL，MySQL 会隐式提交，所以必须放在 commit 之后，别挪进事务里。
commit;

-- ---------- 10.5 把自增抬到 10000 以上 ----------
-- 文件开头承诺"真实业务行从 10000+ 开始，脚本只删 9xxx 段"，但**插过显式 9xxx id 之后，
-- MySQL 会把自增停在 max(id)+1**，也就是 9xxx 里面——不抬上去的话，他自己在界面上发的笔记、
-- 评的价、注册的账号就会落在删除段里，下一次重灌脚本会被当成演示数据一起清掉。
-- 这条 ALTER 才是那句承诺真正成立的地方，少一张表都可能漏掉一类真实数据。
alter table user         auto_increment = 10000;
alter table coupon       auto_increment = 10000;
alter table goods        auto_increment = 10000;
alter table user_coupon  auto_increment = 10000;
alter table orders       auto_increment = 10000;
alter table blog         auto_increment = 10000;
alter table blog_image   auto_increment = 10000;
alter table blog_like    auto_increment = 10000;
alter table blog_comment auto_increment = 10000;
alter table review       auto_increment = 10000;
alter table notification auto_increment = 10000;
alter table follow       auto_increment = 10000;
alter table user_favorite auto_increment = 10000;
alter table user_address  auto_increment = 10000;

select '⑪ 自己给自己发消息的（应为空）' as 检查项;
select id, user_id, from_user_id from notification where id between 9000 and 9999 and user_id = from_user_id;

select '⑫ 消息数与事件数对不上的类型（应为空）' as 检查项;
select t.类型, t.消息数, t.事件数 from (
    select 'LIKE_BLOG' as 类型,
           (select count(*) from notification where type = 'LIKE_BLOG') as 消息数,
           (select count(*) from blog_like bl join blog b on b.id = bl.blog_id
              where b.user_id <> bl.user_id and b.status = 1) as 事件数
    union all
    select 'COMMENT_BLOG',
           (select count(*) from notification where type = 'COMMENT_BLOG'),
           (select count(*) from blog_comment c join blog b on b.id = c.blog_id
              where b.user_id <> c.user_id and b.status = 1)
    union all
    select 'FOLLOW',
           (select count(*) from notification where type = 'FOLLOW'),
           (select count(*) from follow where user_id <> follow_user_id)
) t where t.消息数 <> t.事件数;

-- V14 给 review 加的 status 只有两个合法取值；出现第三个值说明有代码绕过了服务端的白名单。
select '⑮ 评价审核状态不在 (1, 2) 里的（应为空）' as 检查项;
select id, shop_id, status from review where status not in (1, 2);

-- 这一条是第 4 节 not exists + 第 5.5 节置空这套设计的验收：
-- 演示订单引用的券行必须真的存在（跳过的已经被 5.5 置成 NULL，不再指着空气）。
select '⑯ 演示订单挂着不存在的券行（应为空）' as 检查项;
select o.id, o.user_coupon_id, o.discount_amount
from orders o
where o.id between 9000 and 9999
  and o.user_coupon_id is not null
  and not exists (select 1 from user_coupon c where c.id = o.user_coupon_id);

-- 上一条管"引用不存在"，这一条管反向的那种更坏的：**引用存在，但那是用户自己的真券**。
-- 5.5 节原来干的就是这件事（重挂到真实行 → 7.2 把真券翻成已使用），改完靠这条盯住它别回来。
-- 触发条件：任何演示订单的 user_coupon_id 落在 10000+ 段。沙箱实测原件脚本会在这里出 1 行（订单 9001），
-- 修完之后这条必须为空。
select '⑱ 演示订单吃了真实段的券（应为空，非空就是资损级）' as 检查项;
select o.id as 演示订单, o.user_coupon_id as 真实券行, c.user_id as 券的主人, c.status as 券状态
from orders o
         join user_coupon c on c.id = o.user_coupon_id
where o.id between 9000 and 9999
  and o.user_coupon_id not between 9000 and 9999;

-- 反向那条：券翻成"已使用"是因为演示订单，那 order_id 必须还在。
-- 出现"已使用 + order_id 指向已删除的演示单"就是第 0 节解绑没生效，下一次重灌会留下孤儿状态。
select '⑰ 券标着已使用但订单已经不在了（应为空）' as 检查项;
select c.id, c.user_id, c.order_id
from user_coupon c
where c.status = 2
  and c.order_id is not null
  and not exists (select 1 from orders o where o.id = c.order_id);

-- ---------- 11. 灌完之后必须手工做的两件 Redis 清理 ----------
-- ① 关注 Feed 是**推模式**收件箱（ZSet），只有"发布笔记"那条代码路径会往里写。
--    脚本直接插的笔记不会推给任何人，所以已经存在的收件箱里缺这些新内容。
--    把这些键删掉，下一次读 Feed 会按 follow + blog 回源重建（这套兜底本来就是为这个场景写的）：
--      DEL life:feed:follow:{userId}  life:feed:empty:{userId}
-- ② 本轮改了 shop.score（评价均分），所以商家详情缓存必须一起清，
--    否则首页/详情页的 ★ 要等 30 分钟 TTL 自然过期才更新：
--      DEL life:shop:detail:*
-- 本机没有 redis-cli，用 docs/PROGRESS.md 第 7 节那段最小 RESP 探测脚本执行（口令见 memurai.conf）。
