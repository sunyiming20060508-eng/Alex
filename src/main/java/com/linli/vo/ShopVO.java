package com.linli.vo;

import com.linli.entity.Shop;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 商家列表/详情返回对象。字段名对齐 API_CONTRACT.md 第 6 节。
 * 阶段 2 之后数据来自 shop 表，这里只负责把实体里的 0/1、金额、评分转成页面直接能显示的文本。
 *
 * favorite 是**按人**的字段，所以商家详情的 Redis 缓存里存的仍然是 Shop 实体（不含它）——
 * 把某个用户的收藏状态缓存下来，等于把别人的收藏状态发给他。填充一律发生在缓存之后。
 */
public record ShopVO(Long id, String name, String categoryName, String coverUrl, String rating,
                     Integer pricePerPerson, String priceText, String distance, String tag,
                     List<String> tags, String address, String businessHours, String openStatus,
                     boolean favorite, String city, List<String> images) {

    public static ShopVO from(Shop shop) {
        if (shop == null) return null;
        String tag = shop.getTag();
        return new ShopVO(shop.getId(), shop.getName(), shop.getCategoryName(), shop.getCover(),
                rating(shop.getScore()), shop.getMinPrice(), shop.legacyPrice(), shop.getDistance(), tag,
                tag == null || tag.isBlank() ? List.of() : List.of(tag),
                shop.getAddress(), shop.getBusinessHours(), openStatusOf(shop), false, shop.getCity(), List.of());
    }

    /** record 是不可变的，"我"相关的字段只能在读出后整体换一份。 */
    public ShopVO withFavorite(boolean favorite) {
        return favorite == this.favorite ? this : copy(favorite, images);
    }

    /**
     * 图集只在详情里填。它和 favorite 一样是**缓存之外**补的：商家详情的缓存存的是 Shop 实体，
     * 而图集在 shop_image 表里，塞进缓存就等于多一处要和 shop_image 对账的失效点。
     */
    public ShopVO withImages(List<String> urls) {
        List<String> next = urls == null || urls.isEmpty() ? List.of() : List.copyOf(urls);
        return next.equals(images) ? this : copy(favorite, next);
    }

    private ShopVO copy(boolean favorite, List<String> images) {
        return new ShopVO(id, name, categoryName, coverUrl, rating, pricePerPerson, priceText, distance, tag,
                tags, address, businessHours, openStatus, favorite, city, images);
    }

    /** 评分固定一位小数，避免 DECIMAL(2,1) 序列化成 4.90 或 5。 */
    private static String rating(BigDecimal score) {
        return score == null ? null : score.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    /** 后台下线（status=0）和当前时间不在营业段内都算已休息。 */
    private static String openStatusOf(Shop shop) {
        boolean online = shop.getStatus() == null || shop.getStatus() == 1;
        return online && shop.openNow() ? "OPEN" : "CLOSED";
    }
}
