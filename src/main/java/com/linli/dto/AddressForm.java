package com.linli.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * POST/PUT {@code /api/user/me/addresses} 请求体。
 *
 * 列宽和 user_address 表一一对应（@Size 的上限就是建表时的 VARCHAR 长度），
 * 这样"注解放过、数据库报 Data too long"那条路被堵死——V8/V12 修的就是这类长度对不上。
 */
public record AddressForm(
        @NotBlank(message = "请填写收货人")
        @Size(max = 20, message = "收货人最多 20 个字符")
        String consignee,

        @NotBlank(message = "请填写联系电话")
        @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
        String phone,

        @NotBlank(message = "请填写详细地址")
        @Size(max = 255, message = "地址最多 255 个字符")
        String address,

        @Size(max = 10, message = "标签最多 10 个字符")
        String tag,

        /** null 视为"不改动"；新增时第一条会自动成为默认地址。 */
        Boolean isDefault) {
}
