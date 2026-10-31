package com.linli.constant;

/** 密码与注册的规则常量：注解属性要的是编译期常量，所以规则只能放这里，不能塞进配置。 */
public final class AuthConstant {

    public static final int PASSWORD_MIN_LENGTH = 8;
    public static final int PASSWORD_MAX_LENGTH = 32;

    /**
     * 至少一个字母 + 一个数字，长度 8-32，且只允许可见 ASCII（不含空格）。
     * 排除空格是故意的：首尾带空格的密码在"看着输入对了却登不上"之间制造无法自证的歧义，
     * 而服务端悄悄 trim 又会让用户以为空格是密码的一部分。
     */
    public static final String PASSWORD_PATTERN = "^(?=.*[A-Za-z])(?=.*\\d)[\\x21-\\x7e]{8,32}$";

    public static final String PASSWORD_MESSAGE =
        "密码需 " + PASSWORD_MIN_LENGTH + "-" + PASSWORD_MAX_LENGTH + " 位，至少含一个字母和一个数字，不能用空格";

    /** 同一手机号密码登录连续错多少次就锁。 */
    public static final int PASSWORD_MAX_FAILS = 5;

    /** 锁定时长（秒），也是失败计数键的存活时间。 */
    public static final int PASSWORD_LOCK_SECONDS = 600;

    /** BCrypt 工作因子；10 是 2026 年在线系统的常规值，提到 12 只需要改这里并让新密码自然覆盖。 */
    public static final int BCRYPT_STRENGTH = 10;

    private AuthConstant() {}
}
