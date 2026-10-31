package com.linli.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** shop_image 表数据访问。一期只读，写入等商家管理端。 */
@Mapper
public interface ShopImageMapper {

    /**
     * 一家店的图集，按 sort 升序。
     *
     * 只取 url：详情页不需要每张图的 id 和创建时间，多带两列等于把没用的字段缓存进商家详情。
     */
    @Select("select url from shop_image where shop_id = #{shopId} and url <> '' order by sort, id")
    List<String> listUrlsByShop(@Param("shopId") Long shopId);
}
