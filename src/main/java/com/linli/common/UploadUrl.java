package com.linli.common;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 上传地址簿：新地址怎么拼、历史上发出去过的地址有哪些。
 *
 * local 模式的前缀是本站路径（默认 /api/file/images，由静态资源映射读盘）；
 * oss 模式是虚拟主机风格的桶域名 https://{bucket}.{endpoint}，配了
 * linli.upload.oss.public-base-url 就用它（自定义域名/CDN 的场景）。
 *
 * 这两件事放在一起是有原因的：上传适配器要拿它拼地址，OwnImageGuard 要拿它判白名单，
 * 两边各读各的配置迟早漂移成"上传返回的地址自己不认"。而白名单里必须同时留着本地前缀，
 * 否则切到 OSS 之后库里那批 /api/file/images 的老地址全部变成非法输入。
 */
@Component
public class UploadUrl {

    private final String base;
    private final List<String> issuedBases;

    @Autowired
    public UploadUrl(@Value("${linli.upload.mode:local}") String mode,
                     @Value("${linli.upload.public-prefix:/api/file/images}") String localPrefix,
                     @Value("${linli.upload.oss.public-base-url:}") String ossPublicBaseUrl,
                     @Value("${linli.upload.oss.bucket-name:}") String bucketName,
                     @Value("${linli.upload.oss.endpoint:}") String endpoint) {
        if (!"oss".equalsIgnoreCase(trim(mode))) {
            this.base = trimTrailing(localPrefix);
        } else {
            String explicit = trimTrailing(ossPublicBaseUrl);
            if (!explicit.isEmpty()) {
                this.base = explicit;
            } else {
                String bucket = trim(bucketName);
                String host = trim(endpoint).replaceFirst("(?i)^https?://", "");
                if (bucket.isEmpty() || host.isEmpty()) {
                    throw new IllegalStateException(
                            "linli.upload.mode=oss 需要 OSS_BUCKET_NAME 和 OSS_ENDPOINT，或用 linli.upload.oss.public-base-url 直接给出完整地址前缀");
                }
                this.base = "https://" + bucket + "." + host;
            }
        }
        Set<String> bases = new LinkedHashSet<>();
        add(bases, this.base);
        add(bases, localPrefix);
        this.issuedBases = List.copyOf(bases);
    }

    private UploadUrl(String base) {
        this.base = trimTrailing(base);
        Set<String> bases = new LinkedHashSet<>();
        add(bases, this.base);
        this.issuedBases = List.copyOf(bases);
    }

    /** 脱离 Spring 容器（单元测试）时直接给定前缀。 */
    public static UploadUrl fixed(String base) {
        return new UploadUrl(base);
    }

    /** 当前存储的对外地址前缀，不含结尾斜杠。 */
    public String base() {
        return base;
    }

    /** 对象键 -> 能直接放进 img src 的地址。 */
    public String location(String objectKey) {
        return base + "/" + objectKey;
    }

    /** 本平台发过的全部地址前缀，图片白名单按它判，切换存储不影响历史数据。 */
    public List<String> issuedBases() {
        return issuedBases;
    }

    private static void add(Set<String> target, String value) {
        String normalized = trimTrailing(value);
        if (!normalized.isEmpty()) target.add(normalized);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimTrailing(String value) {
        return trim(value).replaceAll("/+$", "");
    }
}
