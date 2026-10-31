package com.linli.entity;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * user 表实体，字段名对应表里的 snake_case 列（map-underscore-to-camel-case 已开启）。
 * nick_name 和 icon 是历史列名，对外仍按 API_CONTRACT.md 叫 nickname / avatar，转换在 vo/UserVO 里做。
 */
@Data
public class User {

    private Long id;

    /** 登录手机号，未脱敏，不直接返回给页面。 */
    private String phone;

    private String nickName;

    /** 头像：可能是上传后的图片地址，也可能是旧的单个文字占位。 */
    private String icon;

    /** 性别：0 保密、1 男、2 女。V5 迁移才加的列，历史数据默认 0。 */
    private Integer gender;

    /** 身份：0 普通用户、1 管理员（V14）。只有 {@code @RequiresAdmin} 的接口会读它。 */
    private Integer role;

    /** 积分余额（V15）。只由 PointsService 在事务里改，别处不要直接写这一列。 */
    private Integer points;

    /**
     * BCrypt 哈希，不是明文；NULL 表示这个账号从没设置过密码，只能验证码登录。
     * 只有 {@code UserMapper.getByPhoneWithSecret} 会把它读出来，其余查询一律不带这一列，
     * 免得哈希顺着某个新加的 VO 或日志漏出去（UserVO 里没有 password 字段，就是这个道理）。
     */
    private String password;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 派生列，不是表字段：{@code password is not null} 的结果，供前端判断要不要引导设置密码。 */
    private Boolean hasPassword;
}
