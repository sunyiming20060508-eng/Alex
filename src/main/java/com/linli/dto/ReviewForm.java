package com.linli.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * POST /api/shops/{shopId}/reviews 请求体。
 *
 * 评的是哪一单由客户端指定（用户在"我的订单"里点"去评价"），但**评价人只认会话**——
 * 这里没有 userId 字段，传了也没人读。订单归属、状态和是否评过都由 Service 现查数据库判定。
 */
public record ReviewForm(
        @NotNull(message = "缺少要评价的订单") Long orderId,
        @NotNull(message = "请给个评分") @Min(value = 1, message = "评分最低 1 星") @Max(value = 5, message = "评分最高 5 星")
        Integer score,
        @NotBlank(message = "请写点评价内容") @Size(max = 1000, message = "评价最多 1000 个字符") String content,
        /** 最多 6 张，且只认本平台上传的地址；库里按逗号分隔存。 */
        @Size(max = 6, message = "一条评价最多 6 张图") List<String> images) {}
