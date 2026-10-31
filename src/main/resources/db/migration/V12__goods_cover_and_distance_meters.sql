-- V12：让"按距离排序"这件事有依据，并补宽 goods.cover
--
-- ① goods.cover 从 VARCHAR(50) 放宽到 255。
--    和 V8 修掉的 blog.cover 是同一个形状：现在套餐图全是 emoji 占位所以没炸，
--    但真实上传地址（/api/file/images/goods/{日期}/{32位}.png）有 58—70 字符，
--    一旦有商家后台或本端能传套餐图，就是一条 500。趁现在没有数据先改，成本为零。
--
-- ② shop 加一列数值距离 distance_meters。
--    发现页文案一直写着"按距离筛选"，但 distance 列存的是展示文本（'520m'、'1.2km'、'上门服务'），
--    字符串排序会把 '860m' 排在 '1.2km' 前面，所以这个参数一直没接。
--    这里不是要替换展示列——'上门服务'这种没有距离的商家仍要显示原文，
--    所以是"展示列保留 + 新增可排序的数值列"，没有距离的留 NULL，排序时排到最后。
ALTER TABLE goods MODIFY COLUMN cover VARCHAR(255) DEFAULT NULL COMMENT '套餐封面：emoji 占位或本平台上传后的图片地址';

ALTER TABLE shop
    ADD COLUMN distance_meters INT DEFAULT NULL COMMENT '可排序的数值距离（米）；NULL 表示"上门服务"这类没有固定距离' AFTER distance;

-- 回填：从展示文本推数值。m 结尾按米、km 结尾按千米换算，其余（上门服务）留 NULL。
-- 这条 UPDATE 只在迁移里跑一次；以后新增商家要同时写两列，或者由后端算，别让文本再当排序依据。
UPDATE shop
SET distance_meters = CASE
                          WHEN distance REGEXP '^[0-9]+(\\.[0-9]+)?m$'
                              THEN CAST(TRIM(TRAILING 'm' FROM distance) AS UNSIGNED)
                          WHEN distance REGEXP '^[0-9]+(\\.[0-9]+)?km$'
                              THEN ROUND(CAST(TRIM(TRAILING 'km' FROM distance) AS DECIMAL(10, 2)) * 1000)
                          ELSE NULL
    END;
