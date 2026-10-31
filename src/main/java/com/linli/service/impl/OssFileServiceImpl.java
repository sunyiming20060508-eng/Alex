package com.linli.service.impl;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import com.linli.common.ApiException;
import com.linli.common.ErrorCode;
import com.linli.common.ImagePolicy;
import com.linli.common.UploadUrl;
import com.linli.service.FileService;
import com.linli.vo.FileVO;
import java.io.ByteArrayInputStream;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

/**
 * 阿里云 OSS 上传适配器：校验还是 {@link ImagePolicy} 那一套，本类只负责 putObject 和拼地址。
 *
 * 和苍穹外卖的 AliOssUtil 不一样的地方：那个工具类每次上传都 new 一个 OSSClient 再 shutdown，
 * 客户端里有连接池，每次请求重建握手太浪费，这里改成容器级单例、随容器关闭释放。
 *
 * 失败时给前端的只有"上传失败，请重试"：errorCode、requestId、桶名只进日志，
 * 那些是排查用的内部信息，不该原样吐给调用方。
 */
public class OssFileServiceImpl implements FileService {

    private static final Logger log = LoggerFactory.getLogger(OssFileServiceImpl.class);

    private final ImagePolicy policy;
    private final OSS oss;
    private final UploadUrl uploadUrl;
    private final String bucketName;

    public OssFileServiceImpl(ImagePolicy policy, OSS oss, UploadUrl uploadUrl, String bucketName) {
        this.policy = policy;
        this.oss = oss;
        this.uploadUrl = uploadUrl;
        this.bucketName = bucketName;
    }

    @Override
    public FileVO upload(MultipartFile file, String biz) {
        ImagePolicy.Accepted image = policy.accept(file, biz);
        String objectKey = policy.objectKey(biz, image.extension());

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(image.bytes().length);
        // 不显式给 Content-Type，桶里存的就是 application/octet-stream，浏览器只能下载不能展示
        metadata.setContentType("image/" + ("jpg".equals(image.extension()) ? "jpeg" : image.extension()));
        try {
            oss.putObject(bucketName, objectKey, new ByteArrayInputStream(image.bytes()), metadata);
        } catch (OSSException e) {
            // 桶那边回的错误（鉴权、配额、对象已存在等）
            log.error("OSS 上传被拒: bucket={} key={} errorCode={} requestId={}",
                    bucketName, objectKey, e.getErrorCode(), e.getRequestId(), e);
            throw new ApiException(ErrorCode.SERVER_ERROR, "图片上传失败，请重试");
        } catch (ClientException e) {
            // 客户端到 OSS 之间的问题（endpoint 写错、网络不通、SDK 配置）
            log.error("OSS 客户端异常: bucket={} key={} {}", bucketName, objectKey, e.getMessage(), e);
            throw new ApiException(ErrorCode.SERVER_ERROR, "图片上传失败，请重试");
        }
        return new FileVO(uploadUrl.location(objectKey), objectKey, image.width(), image.height());
    }

    /** 客户端由 FileStorageConfiguration 创建，交回给容器统一释放，避免连接池泄漏。 */
    @PreDestroy
    public void close() {
        oss.shutdown();
    }
}
