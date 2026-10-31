package com.linli.mapper;

import com.linli.entity.Notification;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * notification 表数据访问。列表和计数都要联表带出触发人的昵称头像，写在
 * resources/mapper/NotificationMapper.xml；写入和标记已读是单条固定语句，留在注解里。
 */
@Mapper
public interface NotificationMapper {

    @Insert("insert into notification (user_id, from_user_id, type, target_id, summary, is_read) "
            + "values (#{userId}, #{fromUserId}, #{type}, #{targetId}, #{summary}, 0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Notification notification);

    List<Notification> pageByUser(@Param("userId") Long userId, @Param("unreadOnly") boolean unreadOnly,
                                  @Param("offset") int offset, @Param("limit") int limit);

    long countByUser(@Param("userId") Long userId, @Param("unreadOnly") boolean unreadOnly);

    @Select("select count(*) from notification where user_id = #{userId} and is_read = 0")
    long countUnread(@Param("userId") Long userId);

    /** 守卫式更新：只能标记自己收件箱里的、而且只从未读变已读。返回 0 表示什么都没发生。 */
    @Update("update notification set is_read = 1 where id = #{id} and user_id = #{userId} and is_read = 0")
    int markRead(@Param("id") Long id, @Param("userId") Long userId);

    @Update("update notification set is_read = 1 where user_id = #{userId} and is_read = 0")
    int markAllRead(@Param("userId") Long userId);
}
