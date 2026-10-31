package com.linli.mapper;

import com.linli.entity.BlogImage;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** blog_image 表数据访问。批量插入用 foreach，明细一次写完。 */
@Mapper
public interface BlogImageMapper {

    @Insert("<script>insert into blog_image (blog_id, url, sort) values "
            + "<foreach collection=\"list\" item=\"i\" separator=\",\">"
            + "(#{i.blogId}, #{i.url}, #{i.sort})</foreach></script>")
    int insertBatch(@Param("list") List<BlogImage> images);

    @Select("select id, blog_id, url, sort, create_time from blog_image where blog_id = #{blogId} order by sort asc, id asc")
    List<BlogImage> listByBlog(@Param("blogId") Long blogId);

    /** 笔记删了就一起删：图片只属于这一篇，留着没有任何读路径会去取它。 */
    @Delete("delete from blog_image where blog_id = #{blogId}")
    int deleteByBlog(@Param("blogId") Long blogId);
}
