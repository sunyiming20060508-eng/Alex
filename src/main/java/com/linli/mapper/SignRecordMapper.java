package com.linli.mapper;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** sign_record 表数据访问。全是定长语句，所以不放 XML。 */
@Mapper
public interface SignRecordMapper {

    /** 撞唯一索引时抛 DuplicateKeyException，由 Service 翻译成"今天已经签过了"。 */
    @Insert("insert into sign_record(user_id, date_sign) values(#{userId}, #{date})")
    int insert(@Param("userId") Long userId, @Param("date") LocalDate date);

    @Select("select count(*) from sign_record where user_id = #{userId} and date_sign between #{from} and #{to}")
    long countBetween(@Param("userId") Long userId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 最近若干次签到的日期（倒序），用来往回连出"连续几天"。 */
    @Select("select date_sign from sign_record where user_id = #{userId} order by date_sign desc limit #{limit}")
    List<LocalDate> listRecentDates(@Param("userId") Long userId, @Param("limit") int limit);
}
