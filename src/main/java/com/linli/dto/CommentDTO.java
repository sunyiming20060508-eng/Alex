package com.linli.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /api/blogs/{id}/comments 请求体。评论人取自当前会话。 */
public record CommentDTO(
        @NotBlank(message = "请填写评论内容")
        @Size(max = 255, message = "评论最多 255 个字符")
        String content) {}
