package com.linli.mapper;

import com.linli.dto.ShopPageQueryDTO;
import com.linli.entity.Shop;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * shop 表数据访问。分页、计数和详情都要左连接 category 拿分类名，属于动态 SQL，全部写在
 * resources/mapper/ShopMapper.xml，禁止在注解里拼 where。
 */
@Mapper
public interface ShopMapper {

    /** 分页查询，limit/offset 由 ShopPageQueryDTO 换算好。 */
    List<Shop> pageQuery(ShopPageQueryDTO query);

    /** 与 pageQuery 同一个 where 条件的总数，用于分页里的 total。 */
    long countQuery(ShopPageQueryDTO query);

    Shop getById(Long id);

    /**
     * 库里真实存在哪些城市（只看在营业的店）。首页的城市下拉读这一份——
     * 给一个写死的城市列表，早晚会出现"选了苏州，结果一家店都没有"。
     */
    @Select("select distinct city from shop where status = 1 and city is not null and city <> '' order by city")
    List<String> listCities();

    /**
     * 下拉框用的最窄一行：只取 id 和 name。
     * 只 select 两列不是洁癖——发帖页原来拉的是完整 ShopVO（评分、人均、分类名、团购数都带着），
     * 换一个商家名列表却把整张卡片的数据搬回来，商家一多就是白烧带宽。
     * status=1 是在营业的店；下架的店出现在"关联商家"下拉里，选了之后详情页又是 404。
     */
    @Select("select id, name from shop where status = 1 order by id limit 200")
    List<Shop> listOptions();

    /**
     * 按 id 批量取商家，带分类名。收藏列表这种"顺序由别的表决定"的场景用它：
     * 先拿到有序的 shopId，再一次查回整行，Service 里按原顺序装配。
     */
    List<Shop> listByIds(@Param("ids") List<Long> ids);

    /**
     * 评价写完后重算展示分（decimal(2,1)）。只在真有过评价时调用，别把种子分清空。
     * SQL 在 ShopMapper.xml——这个 Mapper 的写语句也不例外，保持"shop 的 SQL 只有一处"。
     */
    int updateScore(@Param("id") Long id, @Param("score") BigDecimal score);
}
