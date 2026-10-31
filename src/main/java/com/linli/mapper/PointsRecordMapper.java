package com.linli.mapper;

import com.linli.entity.PointsRecord;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** points_record 表数据访问。 */
@Mapper
public interface PointsRecordMapper {

    @Insert("insert into points_record(user_id, delta, balance, type, ref_id, content) "
            + "values(#{userId}, #{delta}, #{balance}, #{type}, #{refId}, #{content})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PointsRecord record);

    @Select("select id, user_id, delta, balance, type, ref_id, content, create_time from points_record "
            + "where user_id = #{userId} order by id desc limit #{offset}, #{limit}")
    List<PointsRecord> pageByUser(@Param("userId") Long userId,
                                 @Param("offset") int offset,
                                 @Param("limit") int limit);

    @Select("select count(*) from points_record where user_id = #{userId}")
    long countByUser(@Param("userId") Long userId);
}
