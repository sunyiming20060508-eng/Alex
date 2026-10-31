package com.linli.config;

import com.aliyun.oss.OSSClientBuilder;
import com.linli.common.ImagePolicy;
import com.linli.common.UploadUrl;
import com.linli.service.FileService;
import com.linli.service.impl.LocalFileServiceImpl;
import com.linli.service.impl.OssFileServiceImpl;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文件存储的开关：linli.upload.mode = local（默认）| oss，两个适配器实现同一个 FileService，
 * 上层 Controller、校验规则、对象键形状都不用改。
 *
 * 为什么不在两个实现类上直接标 @Service 让 Spring 自动选：那样 mode 写成别的值时
 * 报的是"找不到 FileService bean"，看不出来是拼错了开关。构造函数里先校验一次，
 * 启动阶段就把话说清楚（配置类本身不受 @Conditional 影响，一定会被实例化）。
 *
 * AccessKey 一律走环境变量，仓库里只留占位默认值；mode=oss 而密钥缺失时直接启动失败，
 * 不等到用户点上传才返回 500。
 */
@Configuration(proxyBeanMethods = false)
public class FileStorageConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FileStorageConfiguration.class);
    private static final Set<String> MODES = Set.of("local", "oss");

    public FileStorageConfiguration(@Value("${linli.upload.mode:local}") String mode) {
        String normalized = mode == null ? "" : mode.trim().toLowerCase();
        if (!MODES.contains(normalized)) {
            throw new IllegalStateException("linli.upload.mode 只支持 local、oss，当前值：" + mode);
        }
    }

    @Bean
    @ConditionalOnProperty(prefix = "linli.upload", name = "mode", havingValue = "local", matchIfMissing = true)
    public FileService localFileService(ImagePolicy policy, UploadUrl uploadUrl,
                                        @Value("${linli.upload.dir:uploads}") String dir) {
        return new LocalFileServiceImpl(policy, uploadUrl, dir);
    }

    @Bean
    @ConditionalOnProperty(prefix = "linli.upload", name = "mode", havingValue = "oss")
    public FileService ossFileService(ImagePolicy policy, UploadUrl uploadUrl,
                                      @Value("${linli.upload.oss.endpoint:oss-cn-beijing.aliyuncs.com}") String endpoint,
                                      @Value("${linli.upload.oss.bucket-name:}") String bucketName,
                                      @Value("${linli.upload.oss.access-key-id:}") String accessKeyId,
                                      @Value("${linli.upload.oss.access-key-secret:}") String accessKeySecret) {
        require(accessKeyId, "OSS_ACCESS_KEY_ID");
        require(accessKeySecret, "OSS_ACCESS_KEY_SECRET");
        require(bucketName, "OSS_BUCKET_NAME");
        log.info("图片上传走阿里云 OSS：endpoint={} bucket={}（AccessKey 只从环境变量读，不打印）", endpoint, bucketName);
        return new OssFileServiceImpl(policy,
                new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret), uploadUrl, bucketName);
    }

    private static void require(String value, String envName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("linli.upload.mode=oss 需要先设置环境变量 " + envName + "，密钥不进仓库");
        }
    }
}
