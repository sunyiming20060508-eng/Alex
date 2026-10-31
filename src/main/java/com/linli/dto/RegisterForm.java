package com.linli.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.linli.constant.AuthConstant;

/**
 * POST /api/auth/register 请求体。注册必须先用验证码证明手机号是自己的，
 * 也就没有"别人拿你的手机号注册一个账号"这种事——和验证码登录共用同一份发码额度与校验逻辑。
 */
public record RegisterForm(
        @NotBlank(message = "请填写手机号")
        @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
        String phone,

        @NotBlank(message = "请填写验证码")
        @Pattern(regexp = "^\\d{6}$", message = "验证码是 6 位数字")
        String code,

        @NotBlank(message = "请填写密码")
        @Pattern(regexp = AuthConstant.PASSWORD_PATTERN, message = AuthConstant.PASSWORD_MESSAGE)
        String password,

        @Size(max = 20, message = "昵称长度需为 1-20 个字符")
        String nickname) {}
