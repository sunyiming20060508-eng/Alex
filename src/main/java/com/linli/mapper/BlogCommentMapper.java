package com.linli.mapper;

import com.linli.entity.BlogComment;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

/** blog_comment 表数据访问。作者名走联表，见 XML。 */
@Mapper
public interface BlogCommentMapper {

    @Insert("insert into blog_comment (blog_id, user_id, content) values (#{blogId}, #{userId}, #{content})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(BlogComment comment);

    /** 评论分页：作者昵称和头像一条 join 带出。定义在 resources/mapper/BlogCommentMapper.xml。 */
    List<BlogComment> pageByBlog(@Param("blogId") Long blogId,
                                 @Param("offset") int offset,
                                 @Param("limit") int limit);

    /** 刚插的那条按同一条 join 回读一次：新评论的作者是评论人，不是笔记作者。 */
    BlogComment getById(@Param("id") Long id);

    long countByBlog(@Param("blogId") Long blogId);

    /** 我发出的评论（带所属笔记标题和作者），定义在 XML。 */
    List<BlogComment> pageByAuthor(@Param("userId") Long userId,
                                   @Param("offset") int offset,
                                   @Param("limit") int limit);

    long countByAuthor(@Param("userId") Long userId);

    /**
     * 笔记被作者删掉时连带清评论。**这些评论是别人写的**，所以这一步要在前端的确认文案里
     * 明说清楚（"这条笔记下的 N 条评论会一起消失"），不能悄悄发生；
     * 但语义上没别的选择——评论依附于笔记，笔记没了，"我的评论"列表里那条就会 join 不到上文，
     * 变成一句没有出处的断句。
     */
    @Delete("delete from blog_comment where blog_id = #{blogId}")
    int deleteByBlog(@Param("blogId") Long blogId);
}
