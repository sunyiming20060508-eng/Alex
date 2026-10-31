-- V11：把库里的排序规则统一成 MySQL 8 默认的 utf8mb4_0900_ai_ci
--
-- 现象：直接比两个表的字符串列会报 `ERROR 1267 Illegal mix of collations`。
-- 根因是历史遗留：V1-V3 那批表（user / shop / coupon / orders / review）是照课件的 Navicat 导出脚本建的，
-- 那台导出机用的是 utf8mb4_general_ci；V4 之后建表不再显式写 COLLATE，就跟服务端默认走 utf8mb4_0900_ai_ci。
-- 于是同一个库里躺着两种规则。
--
-- 为什么到现在才炸：本项目所有联表都走 bigint 主键（user_id / shop_id / order_id），
-- 从没按字符串等值join过，所以线上功能一直没受影响。第一次撞上是在写演示数据集的对账检查时
-- 要比较 `user_coupon.name <> coupon.name`——那正是"快照列有没有抄对"该问的问题。
--
-- 统一到 0900_ai_ci 而不是退回 general_ci 的理由：0900 是本机 MySQL 8 的默认值，
-- 新表已经在用它，要改的表数量更少；而且 general_ci 是 5.7 时代的兼容选项，
-- 没有理由为了迁就五张旧表把整个库钉在旧规则上。
--
-- 影响面：CONVERT TO 会重建表（本项目数据量是几十行，秒级）。
-- 排序与比较语义的差别（general_ci 不区分 ü/u 之类扩展）对中文昵称、emoji 占位、手机号、订单号都没有影响。
-- 执行前先 mysqldump 备份，见 docs/PROGRESS.md 第 7 节。
ALTER TABLE user   CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE shop   CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE coupon CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE orders CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE review CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- 兜底：库级默认值也一起改掉，否则以后新建的表虽然默认就是 0900，但万一谁的客户端又带上 general_ci
-- 的建表语句，还是会混进来。（ALTER DATABASE 不影响已有表，只改默认值）
ALTER DATABASE life_service CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
