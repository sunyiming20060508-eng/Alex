package com.linli.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import com.linli.constant.AuthConstant;

/**
 * PUT /api/user/password 请求体。
 *
 * {@code oldPassword} 只在账号**已经设过密码**时必填；从没设过密码（V6 之前的存量账号）
 * 时留空即可直接设置——这类账号当前会话本身就是通过验证码登录拿到的，不需要再验一次旧密码。
 */
public record PasswordForm(
        String oldPassword,

        @NotBlank(message = "请填写新密码")
        @Pattern(regexp = AuthConstant.PASSWORD_PATTERN, message = AuthConstant.PASSWORD_MESSAGE)
        String newPassword) {}
