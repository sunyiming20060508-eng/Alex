package com.linli.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 换绑手机号。三个字段都要：
 *
 * <ul>
 *   <li>{@code newPhone} + {@code code}：证明<b>新手机号是他的</b>（能收到验证码）；</li>
 *   <li>{@code password}：证明<b>这个账号是他的</b>。只验新号验证码等于"谁拿到别人新手机的码就能改别人账号"，
 *       只验密码则是"密码一泄露，账号连手机号一起被搬走"。两样都要，缺一样就是给攻击者留半边门。</li>
 * </ul>
 *
 * <p>没设过密码的账号走不通这条路，服务端会先要求去设置密码——这是刻意的：那种账号唯一的凭据是
 * 原手机验证码，而换绑恰恰要换掉它，用即将被换掉的东西来证明身份没有意义。
 */
public record PhoneChangeForm(
        @NotBlank(message = "请填写新手机号")
        @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
        String newPhone,

        @NotBlank(message = "请填写验证码")
        @Pattern(regexp = "^\\d{4,6}$", message = "验证码格式不正确")
        String code,

        @NotBlank(message = "请填写登录密码")
        String password) {
}
