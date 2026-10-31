package com.linli.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 管理端"改状态"这一个动作的请求体：笔记下架/恢复、评价驳回/通过都用它。
 *
 * 这里只校验"传没传"，**不校验取值合法**：两套状态的字面量不一样
 * （笔记是 0 下架 / 1 展示，评价是 1 通过 / 2 驳回），能判合法的只有各自的服务端，
 * 所以在注解里写 {@code @Range} 等于把业务规则复制到第二个地方，两边迟早对不上。
 */
public record StatusForm(@NotNull(message = "请指定要改成的状态") Integer status) {
}
