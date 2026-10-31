package com.linli.vo;

/**
 * 文件上传返回：url 给前端直接放进 img src，objectKey 是存储侧的对象键，
 * 接 OSS 后 objectKey 就是 bucket 内的 key。宽高读不出来时为 null（例如 webp）。
 */
public record FileVO(String url, String objectKey, Integer width, Integer height) {}
