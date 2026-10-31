package com.linli.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * PUT /api/user/me 请求体，三个字段均可选，只更新传进来的部分。
 * 长度这类"形状"约束交给注解（Controller 上 @Valid 触发），注解对 null 放行、对空串由 min=1 拦下；
 * "传了昵称不能全是空格"要的是业务语义，留在 Service 里判。
 */
public record ProfileForm(@Size(min = 1, max = 20, message = "昵称长度需为 1-20 个字符")
                          String nickname,
                          String avatar,
                          @Min(value = 0, message = "性别只能是 0 保密、1 男、2 女")
                          @Max(value = 2, message = "性别只能是 0 保密、1 男、2 女")
                          Integer gender) {}
