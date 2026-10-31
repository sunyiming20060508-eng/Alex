package com.linli.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import com.linli.common.ApiException;
import com.linli.common.ErrorCode;
import com.linli.common.ImagePolicy;
import com.linli.common.UploadUrl;
import com.linli.vo.FileVO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * OSS 上传适配器的单元测试：不联网，OSS 客户端用 mock。
 *
 * 这里守的三件事都是配置切换最容易出问题的地方——校验规则不能因为换成 OSS 就变松、
 * 地址要拼成桶的虚拟主机域名、失败时不能把桶名和内部错误码吐给前端。
 */
class OssFileServiceImplTest {

    private static final String BUCKET = "linli-demo";
    private static final String ENDPOINT = "oss-cn-beijing.aliyuncs.com";

    private final OSS oss = mock(OSS.class);

    private final OssFileServiceImpl service = new OssFileServiceImpl(
            new ImagePolicy(2), oss, UploadUrl.fixed("https://" + BUCKET + "." + ENDPOINT), BUCKET);

    @Test
    @DisplayName("上传成功：对象键进桶、Content-Type 是图片、返回桶域名地址")
    void putsObjectAndReturnsBucketUrl() {
        byte[] jpeg = image("jpg", 32, 18);

        FileVO vo = service.upload(new MockMultipartFile("file", "cover.jpg", "image/jpeg", jpeg), "blog");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ObjectMetadata> meta = ArgumentCaptor.forClass(ObjectMetadata.class);
        verify(oss).putObject(eq(BUCKET), key.capture(), any(InputStream.class), meta.capture());

        assertThat(vo.objectKey()).matches("blog/\\d{4}-\\d{2}-\\d{2}/[0-9a-f]{32}\\.jpg");
        assertThat(vo.url()).isEqualTo("https://" + BUCKET + "." + ENDPOINT + "/" + vo.objectKey());
        assertThat(vo.width()).isEqualTo(32);
        assertThat(vo.height()).isEqualTo(18);
        // 不给 Content-Type 的话桶里存成 application/octet-stream，浏览器只会下载不会显示
        assertThat(meta.getValue().getContentType()).isEqualTo("image/jpeg");
        assertThat(meta.getValue().getContentLength()).isEqualTo(jpeg.length);
        assertThat(key.getValue()).isEqualTo(vo.objectKey());
    }

    @Test
    @DisplayName("校验规则和本地模式同一份：伪装的图片到不了 OSS")
    void rejectsFakeImageBeforeCallingOss() {
        MultipartFile upload = new MockMultipartFile("file", "a.png", "image/png", "not an image".getBytes());

        assertThatThrownBy(() -> service.upload(upload, "avatar"))
                .isInstanceOf(ApiException.class).hasMessage("文件内容不是有效图片");
        verifyNoInteractions(oss);
    }

    @Test
    @DisplayName("桶那边报错：前端只看到一句可重试的提示")
    void hidesOssDetailsFromCaller() {
        when(oss.putObject(any(String.class), any(String.class), any(InputStream.class), any(ObjectMetadata.class)))
                .thenThrow(new OSSException("The signature does not match", "SignatureDoesNotMatch", "req-1",
                        "host-1", "resource-1", "body-1", "PUT"));

        assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", "a.png", "image/png", image("png", 4, 4)), "avatar"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SERVER_ERROR))
                .hasMessage("图片上传失败，请重试");
    }

    @Test
    @DisplayName("客户端到桶的网络异常也走同一条兜底")
    void mapsClientException() {
        when(oss.putObject(any(String.class), any(String.class), any(InputStream.class), any(ObjectMetadata.class)))
                .thenThrow(new ClientException("连接超时"));

        assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", "a.png", "image/png", image("png", 4, 4)), "avatar"))
                .isInstanceOf(ApiException.class).hasMessage("图片上传失败，请重试");
    }

    private static byte[] image(String format, int width, int height) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
