package com.linli.service;

import com.linli.vo.FileVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件服务出口。存到哪里由 linli.upload.mode 决定（config/FileStorageConfiguration 选实现），
 * 本地磁盘和阿里云 OSS 两个适配器返回同样的字段（url/objectKey/宽高）。
 */
public interface FileService {

    /**
     * @param biz 业务分类，只接受白名单内的取值；对象键按 {biz}/{日期}/{uuid}.{ext} 生成
     */
    FileVO upload(MultipartFile file, String biz);
}
