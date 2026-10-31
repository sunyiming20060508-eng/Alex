package com.linli.mapper;

import com.linli.dto.AdminBlogQueryDTO;
import com.linli.dto.BlogPageQueryDTO;
import com.linli.entity.Blog;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * blog 表数据访问。列表都是"作者信息一起带出来"的一条 join，
 * 避免在 Service 里为每条笔记再查一次用户（N+1）。
 */
@Mapper
public interface BlogMapper {

    String COLUMNS = "b.id, b.user_id, b.shop_id, b.title, b.content, b.cover, b.like_count, "
            + "b.comment_count, b.status, b.create_time, b.update_time, "
            + "u.nick_name as author_name, u.icon as author_icon, s.name as shop_name";

    @Insert("insert into blog (user_id, shop_id, title, content, cover, like_count, comment_count, status) "
            + "values (#{userId}, #{shopId}, #{title}, #{content}, #{cover}, 0, 0, 1)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Blog blog);

    @Select("select " + COLUMNS + " from blog b "
            + "left join user u on u.id = b.user_id "
            + "left join shop s on s.id = b.shop_id "
            + "where b.id = #{id} and b.status = 1")
    Blog getOnlineById(@Param("id") Long id);

    /** 管理台用：不带 status 条件，"这条存在吗"和"它现在什么状态"都要能问出来。 */
    @Select("select " + COLUMNS + " from blog b "
            + "left join user u on u.id = b.user_id "
            + "left join shop s on s.id = b.shop_id "
            + "where b.id = #{id}")
    Blog getAnyById(@Param("id") Long id);

    /** 我发的笔记（XML）：**含被下架的**，作者本人要知道自己写的东西去哪了。 */
    List<Blog> pageMine(@Param("userId") Long userId, @Param("offset") int offset, @Param("limit") int limit);

    long countMine(@Param("userId") Long userId);

    /** 管理台分页与计数（XML）：看得见已下架的，这是它和 pageQuery 唯一的区别。 */
    List<Blog> pageAdmin(AdminBlogQueryDTO query);

    long countAdmin(AdminBlogQueryDTO query);

    /** 下架 / 恢复只改这一列。返回 0 表示这条笔记不存在，由调用方翻译成语义错误。 */
    @Update("update blog set status = #{status} where id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    /** 推荐流：按发布时间倒序分页，作者和商家名一次带出。 */
    List<Blog> pageQuery(BlogPageQueryDTO query);

    long countQuery(BlogPageQueryDTO query);

    /**
     * 关注 Feed 回源重建用：取这批作者近期发布的笔记（id + 发布时间戳当 score）。
     * 定义在 resources/mapper/BlogMapper.xml，IN 列表长度由粉丝数量决定。
     */
    List<Blog> listRecentByAuthors(@Param("authorIds") List<Long> authorIds, @Param("limit") int limit);

    /**
     * 按 id 批量取回笔记（关注 Feed 拿到的是 id 列表，正文和作者要一次查回来）。
     * 顺序由调用方按 ZSet 的返回顺序在内存里排，SQL 不管——省掉 ORDER BY FIELD 这种没法走索引的写法。
     */
    List<Blog> listByIds(@Param("ids") List<Long> ids, @Param("userId") Long userId);

    /** 点赞计数 +1/-1；减到 0 就停在 0，不靠前端保证不越界。 */
    @Update("update blog set like_count = like_count + #{delta} "
            + "where id = #{id} and (#{delta} > 0 or like_count > 0)")
    int updateLikeCount(@Param("id") Long id, @Param("delta") int delta);

    @Update("update blog set comment_count = comment_count + 1 where id = #{id}")
    int increaseCommentCount(@Param("id") Long id);

    /**
     * 删掉某人的某篇笔记。**id 和 user_id 必须同时进 where**：
     * 归属判断在 Service 里查过一次了，但"查过了"和"删的时候还成立"之间隔着一个并发窗口
     * （管理端同一刻下架、或者 id 传错），把条件写进语句才是最后一道守卫。
     * 原来这里是一条没有 user_id 的 {@code deleteById}，全项目没人调用——
     * 一条无守卫的删除语句留着，早晚会有人顺手用它。
     */
    @Delete("delete from blog where id = #{id} and user_id = #{userId}")
    int deleteByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);
}
