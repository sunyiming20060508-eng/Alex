package com.linli.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * blog_like 表数据访问。没有实体类：这条关系只有"存不存在"和"某人赞过哪些"两种问法，
 * 一行一个 id 就够，造实体反而多一层没用的转换。
 */
@Mapper
public interface BlogLikeMapper {

    /** 一人一赞由 uk_blog_user 兜底；重复点赞会抛 DuplicateKeyException，由 Service 翻译成"已经点过"。 */
    @Insert("insert into blog_like (blog_id, user_id) values (#{blogId}, #{userId})")
    int insert(@Param("blogId") Long blogId, @Param("userId") Long userId);

    @Delete("delete from blog_like where blog_id = #{blogId} and user_id = #{userId}")
    int delete(@Param("blogId") Long blogId, @Param("userId") Long userId);

    /** 笔记被作者删掉时连带清点赞：不清就是一堆指向不存在笔记的行，而且没人会再去读它们。 */
    @Delete("delete from blog_like where blog_id = #{blogId}")
    int deleteByBlog(@Param("blogId") Long blogId);

    @Select("select count(*) from blog_like where blog_id = #{blogId} and user_id = #{userId}")
    int exists(@Param("blogId") Long blogId, @Param("userId") Long userId);

    /** 详情页要标出"这批笔记我哪几篇点过"，一次 IN 查回来，别逐条问。 */
    List<Long> listLikedBlogIds(@Param("userId") Long userId, @Param("blogIds") List<Long> blogIds);
}
