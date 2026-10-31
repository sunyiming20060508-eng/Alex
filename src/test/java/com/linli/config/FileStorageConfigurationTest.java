package com.linli.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.linli.common.ImagePolicy;
import com.linli.common.UploadUrl;
import com.linli.service.FileService;
import com.linli.service.impl.LocalFileServiceImpl;
import com.linli.service.impl.OssFileServiceImpl;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 存储开关的单元测试。密钥缺失这类配置错误必须在启动阶段就说清楚，
 * 而不是等用户点上传才收到一个 500——那种问题现场最难定位。
 */
class FileStorageConfigurationTest {

    private static final String ENDPOINT = "oss-cn-beijing.aliyuncs.com";
    private static final String BUCKET = "linli-demo";

    @TempDir
    Path tempDir;

    private final ImagePolicy policy = new ImagePolicy(2);
    private final FileStorageConfiguration configuration = new FileStorageConfiguration("local");

    @Test
    @DisplayName("mode 拼错时先报开关写错了，而不是「找不到 FileService bean」")
    void rejectsUnknownMode() {
        assertThatThrownBy(() -> new FileStorageConfiguration("cloude"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("linli.upload.mode");
    }

    @Test
    @DisplayName("默认 local：图片落盘，地址是本站前缀")
    void localIsTheDefault() throws Exception {
        FileService service = configuration.localFileService(policy, UploadUrl.fixed("/api/file/images"), tempDir.toString());

        assertThat(service).isInstanceOf(LocalFileServiceImpl.class);
        String url = service.upload(new MockMultipartFile("file", "a.png", "image/png", png()), "common").url();
        assertThat(url).startsWith("/api/file/images/common/");
        assertThat(tempDir.resolve(url.substring("/api/file/images/".length()))).exists();
    }

    @Test
    @DisplayName("mode=oss 但配置不齐：启动即失败，并点名要配哪个环境变量")
    void ossModeFailsFastWithoutCredentials() {
        FileStorageConfiguration oss = new FileStorageConfiguration("oss");
        UploadUrl url = new UploadUrl("oss", "/api/file/images", "", BUCKET, ENDPOINT);

        assertThatThrownBy(() -> oss.ossFileService(policy, url, ENDPOINT, BUCKET, "", "sk"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OSS_ACCESS_KEY_ID");
        assertThatThrownBy(() -> oss.ossFileService(policy, url, ENDPOINT, BUCKET, "ak", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OSS_ACCESS_KEY_SECRET");
        assertThatThrownBy(() -> oss.ossFileService(policy, url, ENDPOINT, "", "ak", "sk"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OSS_BUCKET_NAME");
    }

    @Test
    @DisplayName("mode=oss 且配置齐全：拿到 OSS 适配器，地址前缀是桶域名")
    void ossModeBuildsOssAdapter() {
        FileStorageConfiguration oss = new FileStorageConfiguration("oss");
        UploadUrl url = new UploadUrl("oss", "/api/file/images", "", BUCKET, ENDPOINT);

        FileService service = oss.ossFileService(policy, url, ENDPOINT, BUCKET, "ak", "sk");

        assertThat(service).isInstanceOf(OssFileServiceImpl.class);
        assertThat(url.location("common/2026-10-26/a.png"))
                .isEqualTo("https://" + BUCKET + "." + ENDPOINT + "/common/2026-10-26/a.png");
    }

    private static byte[] png() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
