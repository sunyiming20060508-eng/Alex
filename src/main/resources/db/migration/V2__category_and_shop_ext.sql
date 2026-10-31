-- 阶段 2：商家和分类落库（本脚本只执行一次，重复执行会因列已存在而报错）。
-- shop 表原本是 0 行空表，所以把文本列 category 直接换成 category_id，避免同一个分类存两份。

CREATE TABLE IF NOT EXISTS category (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(50) NOT NULL UNIQUE,
  sort INT DEFAULT 0,
  status TINYINT DEFAULT 1 COMMENT '1 显示 0 隐藏',
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

ALTER TABLE shop ADD COLUMN category_id BIGINT AFTER name;
ALTER TABLE shop ADD COLUMN area VARCHAR(50) AFTER category_id;
ALTER TABLE shop ADD COLUMN min_price INT AFTER score;
ALTER TABLE shop ADD COLUMN distance VARCHAR(20) AFTER min_price;
ALTER TABLE shop ADD COLUMN tag VARCHAR(50) AFTER distance;
ALTER TABLE shop DROP COLUMN category;
CREATE INDEX idx_shop_category ON shop (category_id, status);
CREATE INDEX idx_shop_name ON shop (name);

INSERT INTO category (id, name, sort) VALUES
  (1, '杭帮菜', 10),
  (2, '咖啡', 20),
  (3, '丽人', 30),
  (4, '江浙菜', 40),
  (5, '运动健身', 50),
  (6, '面包甜点', 60),
  (7, '家政服务', 70),
  (8, '休闲娱乐', 80);

-- 数据来自原先 ShopService 的内存列表，字段一一对应，页面显示效果不变。
INSERT INTO shop (id, name, category_id, area, score, min_price, distance, tag, cover, address, business_hours, status) VALUES
  (1, '江南小馆·杭帮菜', 1, '湖滨商圈', 4.9, 88, '520m', '午市特惠', '🍲', '杭州市西湖区湖滨路附近', '10:00 - 22:00', 1),
  (2, '拾光咖啡馆', 2, '西湖边', 4.8, 42, '1.2km', '打卡好店', '☕', '杭州市西湖区北山街附近', '08:00 - 20:00', 1),
  (3, '悦己美发造型', 3, '城西银泰', 4.7, 128, '2.4km', '新客立减', '💇', '杭州市拱墅区银泰城附近', '09:30 - 21:30', 1),
  (4, '西溪里·本帮菜', 4, '西溪湿地', 4.6, 96, '3.1km', '双人套餐', '🥢', '杭州市西湖区西溪路附近', '11:00 - 21:30', 1),
  (5, '云朵瑜伽馆', 5, '钱江新城', 4.9, 68, '1.8km', '体验课 ¥9.9', '🧘', '杭州市上城区富春路附近', '07:00 - 22:00', 1),
  (6, '花间里烘焙', 6, '武林广场', 4.8, 35, '860m', '第二件半价', '🥐', '杭州市拱墅区武林路附近', '08:00 - 21:00', 1),
  (7, '邻家家政服务', 7, '全城可约', 4.7, 79, '上门服务', '首次立减', '🧹', '杭州市主城区上门服务', '08:00 - 20:00', 1),
  (8, '星光私人影院', 8, '湖滨商圈', 4.5, 59, '1.5km', '周末特惠', '🎬', '杭州市上城区延安路附近', '12:00 - 02:00', 1);
