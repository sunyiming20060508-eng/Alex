package com.linli.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * POST /api/blogs 请求体。作者取自当前会话，不接受前端传 userId——
 * 否则任何人都能冒名发帖。图片同理：只接受本平台上传目录里的地址。
 */
public record BlogPublishDTO(
        @NotBlank(message = "请填写标题")
        @Size(max = 100, message = "标题最多 100 个字符")
        String title,

        @NotBlank(message = "请填写正文")
        @Size(max = 2000, message = "正文最多 2000 个字符")
        String content,

        /** 关联商家，可以为空（纯生活记录）。存在性由 Service 查库判。 */
        Long shopId,

        /** 图片地址列表，最多 9 张；封面取第一张。 */
        @Size(max = 9, message = "一篇笔记最多 9 张图")
        List<String> images) {}
