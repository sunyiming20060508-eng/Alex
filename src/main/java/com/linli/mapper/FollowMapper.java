package com.linli.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * follow 表数据访问。同样不造实体：关注关系只有"存不存在""我关注了谁""谁关注了我"三种问法。
 * 取关是删行——uk_follow 只有一条，软删会让"取消后又关注"撞唯一索引。
 */
@Mapper
public interface FollowMapper {

    /** 重复关注由 uk_follow 兜底，Service 把 DuplicateKeyException 翻译成"已经关注过"。 */
    @Insert("insert into follow (user_id, follow_user_id) values (#{userId}, #{targetUserId})")
    int insert(@Param("userId") Long userId, @Param("targetUserId") Long targetUserId);

    @Delete("delete from follow where user_id = #{userId} and follow_user_id = #{targetUserId}")
    int delete(@Param("userId") Long userId, @Param("targetUserId") Long targetUserId);

    @Select("select count(*) from follow where user_id = #{userId} and follow_user_id = #{targetUserId}")
    int exists(@Param("userId") Long userId, @Param("targetUserId") Long targetUserId);

    /** 我关注了谁——关注 Feed 的收件箱键就是按这批 id 算的。 */
    @Select("select follow_user_id from follow where user_id = #{userId} order by id desc limit 500")
    List<Long> listFollowees(@Param("userId") Long userId);

    /** 发布笔记时要推给谁——一次取全部粉丝，粉丝量级大了要改成分批。 */
    @Select("select user_id from follow where follow_user_id = #{authorId}")
    List<Long> listFollowers(@Param("authorId") Long authorId);

    @Select("select count(*) from follow where user_id = #{userId}")
    long countFollowees(@Param("userId") Long userId);

    @Select("select count(*) from follow where follow_user_id = #{userId}")
    long countFollowers(@Param("userId") Long userId);
}
