package com.linli.mapper;

import com.linli.entity.UserAddress;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * user_address 表数据访问。全是定长语句，没有需要动态拼的，所以不放 XML。
 *
 * 两条规矩贯穿所有读写：
 * 1. **按 id 取和改一律带 {@code user_id} 守卫**（{@code #} 占位符），所以"改到别人的地址"
 *    在 SQL 层就不可能，不依赖 Service 先查一遍；影响 0 行即"不存在或不是你的"，回同一个 404。
 * 2. 默认地址的互斥靠"先清后置"两条语句，由 Service 放进同一个事务。
 */
@Mapper
public interface UserAddressMapper {

    String COLUMNS = "id, user_id, consignee, phone, address, tag, is_default, create_time, update_time";

    @Insert("insert into user_address(user_id, consignee, phone, address, tag, is_default) "
            + "values(#{userId}, #{consignee}, #{phone}, #{address}, #{tag}, #{isDefault})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserAddress address);

    @Select("select " + COLUMNS + " from user_address where id = #{id} and user_id = #{userId}")
    UserAddress getByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    /** 列表顺序就是界面顺序：默认在最前，其余按新增倒序，和 idx_address_user 对齐。 */
    @Select("select " + COLUMNS + " from user_address where user_id = #{userId} order by is_default desc, id desc")
    List<UserAddress> listByUser(@Param("userId") Long userId);

    /**
     * 上限那道守卫专用：带 {@code for update}，所以"数一下还有没有位置"和"插入"之间不会被
     * 并发请求插进来。InnoDB 在 user_id 这个二级索引范围上加的是 next-key 锁，
     * 连"该用户当前一行都没有"时的插入位置（间隙）一起锁住，所以 0 行的首次添加同样安全。
     * 2026-10-28 实测：持锁事务里这条语句让另一个连接的 insert 等了 2.98 秒，
     * 去掉 {@code for update} 的对照组只花 0.11 秒就插进去了（WORKLOG S-134）。
     * <b>只在事务里调</b>：出了事务锁立刻释放，等于白写。
     */
    @Select("select count(*) from user_address where user_id = #{userId} for update")
    long countOfUserForUpdate(@Param("userId") Long userId);

    @Update("update user_address set consignee = #{consignee}, phone = #{phone}, address = #{address}, "
            + "tag = #{tag} where id = #{id} and user_id = #{userId}")
    int update(UserAddress address);

    @Delete("delete from user_address where id = #{id} and user_id = #{userId}")
    int delete(@Param("id") Long id, @Param("userId") Long userId);

    @Update("update user_address set is_default = 0 where user_id = #{userId} and is_default = 1")
    int clearDefault(@Param("userId") Long userId);

    @Update("update user_address set is_default = 1 where id = #{id} and user_id = #{userId}")
    int markDefault(@Param("id") Long id, @Param("userId") Long userId);
}
