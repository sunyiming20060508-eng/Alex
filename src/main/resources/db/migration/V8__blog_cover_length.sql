-- V8：blog.cover 装不下真实的图片地址
--
-- V7 里 cover 是 VARCHAR(50)，因为那时它只打算放表情占位（抄的 shop.cover 那一套）。
-- 但发布笔记的实际规则是"封面取第一张上传图"，而上传后的地址形如
-- /api/file/images/blog/2026-10-25/aa5b65ab50eb4505a0cbe9955991dd89.png —— 58 个字符，
-- 带业务目录和扩展名最长约 70，50 直接被 MySQL 拒绝：
-- Data truncation: Data too long for column 'cover' at row 1（浏览器实测发布带图笔记时报 50001）。
-- blog_image.url 是 500，所以只有封面这一列受影响。
ALTER TABLE blog
    MODIFY COLUMN cover VARCHAR(255) DEFAULT NULL COMMENT '列表封面：第一张上传图的地址，没图时是表情占位';
