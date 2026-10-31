package com.linli.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * shop 表实体。category_name 是查询时左连接 category 表带出来的冗余字段，表里只有 category_id。
 * cover 现在存的是列表页用的文字/表情，接入图片后换成 /api/file/images 下的地址，字段不变。
 */
@Data
public class Shop {

    private Long id;

    private String name;

    private Long categoryId;

    /** 分类名，来自 category 表；列表页的 categoryName 用它。 */
    private String categoryName;

    /** 所在商圈，仅用于兼容接口的 "分类 · 商圈" 文本。 */
    private String area;

    /** 所属城市（V15）。首页城市筛选按这一列走，area 是城市内的商圈。 */
    private String city;

    private BigDecimal score;

    /** 人均消费（元），null 表示"到店消费"。 */
    private Integer minPrice;

    /** 展示用的距离文本，如 520m、上门服务。 */
    private String distance;

    private String tag;

    private String cover;

    private String address;

    private String businessHours;

    /** 1 营业中，0 已休息。 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 兼容旧 /shop/list 的 "分类 · 商圈" 文本，不落库，只在这里拼一次。 */
    public String legacyType() {
        if (categoryName == null) return area;
        return area == null || area.isBlank() ? categoryName : categoryName + " · " + area;
    }

    /** "人均 ¥88"；没有人均价时返回 null，由前端决定显示"到店消费"。 */
    public String legacyPrice() {
        return minPrice == null ? null : "人均 ¥" + minPrice;
    }

    /** 营业时间是否覆盖当前时刻，只看 HH:mm - HH:mm 这种简单格式，格式不认识时按营业中处理。 */
    public boolean openNow() {
        if (businessHours == null) return true;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})").matcher(businessHours);
        if (!matcher.find()) return true;
        int now = java.time.LocalTime.now().getHour() * 60 + java.time.LocalTime.now().getMinute();
        int begin = Integer.parseInt(matcher.group(1)) * 60 + Integer.parseInt(matcher.group(2));
        int end = Integer.parseInt(matcher.group(3)) * 60 + Integer.parseInt(matcher.group(4));
        return end >= begin ? now >= begin && now <= end : now >= begin || now <= end;
    }
}
