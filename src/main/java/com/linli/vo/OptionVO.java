package com.linli.vo;

/**
 * 下拉框用的最窄形状：只有 id 和 name。
 *
 * 为什么不直接复用 {@link ShopVO}：发帖页的「关联商家」下拉只需要一个名字，
 * 但 ShopVO 带着评分、人均、距离、分类、图集、开放状态十六列——那是列表卡片的形状。
 * 原来这一页拉的是 pageSize=50 的完整 ShopVO，等于为了一个 &lt;select&gt; 把整排卡片的数据搬回家；
 * 商家数量一上千，这一发请求就是几十倍于必要的字节，而且它还占着商家列表那条缓存的序列化开销。
 *
 * 单独一个 VO 的另一个好处：契约上就写明了"这里没有别的字段"，
 * 前端不会哪天顺手去读 option.rating，把最窄的形状又用胖。
 */
public record OptionVO(Long id, String name) {
}
