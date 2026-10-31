package com.linli.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * user_favorite 表数据访问。收藏是"一人一店一条"的关系表，没有需要动态拼条件的查询，
 * 所以全部走注解；唯一一条动态 SQL（IN 列表）在 FavoriteMapper.xml。
 */
@Mapper
public interface FavoriteMapper {

    /** 重复收藏由 uk_favorite_user_shop 拦下，Service 把冲突翻译成"已经是收藏了"。 */
    @Insert("insert into user_favorite(user_id, shop_id) values(#{userId}, #{shopId})")
    int insert(@Param("userId") Long userId, @Param("shopId") Long shopId);

    /** 取消收藏就是删行，删 0 行是 no-op（幂等），不报错。 */
    @Delete("delete from user_favorite where user_id = #{userId} and shop_id = #{shopId}")
    int delete(@Param("userId") Long userId, @Param("shopId") Long shopId);

    @Select("select count(*) from user_favorite where user_id = #{userId} and shop_id = #{shopId}")
    int countOne(@Param("userId") Long userId, @Param("shopId") Long shopId);

    /** 我的列表：按收藏时间倒序，id 单调，比 create_time 更细。 */
    @Select("select shop_id from user_favorite where user_id = #{userId} order by id desc limit #{offset}, #{limit}")
    List<Long> pageShopIds(@Param("userId") Long userId, @Param("offset") int offset, @Param("limit") int limit);

    @Select("select count(*) from user_favorite where user_id = #{userId}")
    long countOfUser(@Param("userId") Long userId);

    /** 一页商家里"我收藏了哪些"，一次 IN 查完，不在循环里逐条问。SQL 在 XML。 */
    List<Long> filterFavoriteShopIds(@Param("userId") Long userId, @Param("ids") List<Long> shopIds);
}
