package com.linli.entity;

import java.time.LocalDateTime;
import lombok.Data;

/** blog_image 表实体：笔记里的图片，一条一行，按 sort 展示。 */
@Data
public class BlogImage {

    private Long id;

    private Long blogId;

    /** 本平台上传后的相对地址；发布接口只接受自家上传目录前缀，外部 URL 一律拒。 */
    private String url;

    private Integer sort;

    private LocalDateTime createTime;
}
