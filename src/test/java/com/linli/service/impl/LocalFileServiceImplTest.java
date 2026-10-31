package com.linli.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.linli.common.ApiException;
import com.linli.common.ImagePolicy;
import com.linli.common.UploadUrl;
import com.linli.vo.FileVO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * 本地磁盘上传适配器的单元测试。写文件用 @TempDir，不碰开发机上的 backend/uploads。
 */
class LocalFileServiceImplTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("上传成功：按对象键落盘，返回本站地址和宽高")
    void writesFileUnderObjectKey() throws Exception {
        byte[] png = png(20, 10);

        FileVO vo = service().upload(new MockMultipartFile("file", "a.png", "image/png", png), "avatar");

        assertThat(vo.objectKey()).matches("avatar/\\d{4}-\\d{2}-\\d{2}/[0-9a-f]{32}\\.png");
        assertThat(vo.url()).isEqualTo("/api/file/images/" + vo.objectKey());
        assertThat(vo.width()).isEqualTo(20);
        assertThat(vo.height()).isEqualTo(10);
        assertThat(Files.readAllBytes(tempDir.resolve(vo.objectKey()))).isEqualTo(png);
    }

    @Test
    @DisplayName("内容不是图片：一个字节都不写")
    void rejectsFakeImage() {
        MultipartFile upload = new MockMultipartFile("file", "a.png", "image/png", "not an image".getBytes());

        assertThatThrownBy(() -> service().upload(upload, "avatar"))
                .isInstanceOf(ApiException.class).hasMessage("文件内容不是有效图片");
        assertThat(tempDir).isEmptyDirectory();
    }

    private LocalFileServiceImpl service() {
        return new LocalFileServiceImpl(new ImagePolicy(2), UploadUrl.fixed("/api/file/images"), tempDir.toString());
    }

    private static byte[] png(int width, int height) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
