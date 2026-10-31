package com.linli.mapper;

import com.linli.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * user 表数据访问。只有按昵称/头像的部分更新放在 XML 里，其余用注解即可。
 *
 * 普通查询带一个派生列 {@code has_password}（布尔），用来让前端判断"这个账号要不要补设密码"；
 * 它只回答有没有，不把哈希本身读出来，所以照旧漏不出任何密码信息。
 */
@Mapper
public interface UserMapper {

    String COLUMNS = "id, phone, nick_name, icon, gender, role, points, create_time, update_time, "
            + "(password is not null and password <> '') as has_password";

    @Select("select " + COLUMNS + " from user where phone = #{phone}")
    User getByPhone(String phone);

    @Select("select " + COLUMNS + " from user where id = #{id}")
    User getById(Long id);

    /**
     * 密码登录专用：只有这一条把 password 列读出来。
     * 其它查询故意不带这一列，这样"顺手把 getByPhone 的结果塞进某个返回体"也漏不出哈希。
     */
    @Select("select " + COLUMNS + ", password from user where phone = #{phone}")
    User getByPhoneWithSecret(String phone);

    /** 改密码专用：按 id 取带哈希的那一份，避免"先查手机号再查哈希"两次往返。 */
    @Select("select " + COLUMNS + ", password from user where id = #{id}")
    User getByIdWithSecret(Long id);

    /**
     * 加/扣积分。带 {@code points + delta >= 0} 的守卫：扣分只能扣到 0 为止，
     * 不会出现"余额是负的"这种账，也不会因为并发扣两次而超扣（影响 0 行即没扣动）。
     */
    @Update("update user set points = points + #{delta} where id = #{id} and points + #{delta} >= 0")
    int updatePoints(@Param("id") Long id, @Param("delta") int delta);

    /**
     * 换绑手机号。条件里带 id 就够了——{@code uk_phone} 会在别人已经占用这个号时把更新挡成
     * DuplicateKeyException，由 Service 翻译成人话。
     */
    @Update("update user set phone = #{phone} where id = #{id}")
    int updatePhone(@Param("id") Long id, @Param("phone") String phone);

    /** 设置或修改密码；password 传的是 BCrypt 哈希，绝不调用方传明文。 */
    @Update("update user set password = #{password} where id = #{id}")
    int updatePassword(@Param("id") Long id, @Param("password") String password);

    /**
     * 首次验证码登录自动建号：只插手机号，昵称、性别、时间走表默认值，回填自增 id。
     * 这样建出来的账号没有密码，前端靠 hasPassword 弹窗引导补设。
     */
    @Insert("insert into user(phone) values(#{phone})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    /**
     * 注册建号：手机号必填，昵称和密码传了才写这一列（没传的走表默认值）。
     * 不能复用 {@link #insert}——那条只插 phone，注册要是用它，密码会被静默丢弃。
     * 定义在 resources/mapper/UserMapper.xml。
     */
    int insertRegistered(User user);

    /** 定义在 resources/mapper/UserMapper.xml：两个字段都可选，只更新传进来的那个。 */
    int updateProfile(User user);
}
