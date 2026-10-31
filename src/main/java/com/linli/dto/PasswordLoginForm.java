package com.linli.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** POST /api/auth/login/password 请求体。密码这里不加强度注解：老密码可能定于早期规则，登录只负责比对。 */
public record PasswordLoginForm(
        @NotBlank(message = "请填写手机号")
        @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
        String phone,

        @NotBlank(message = "请填写密码")
        String password) {}
