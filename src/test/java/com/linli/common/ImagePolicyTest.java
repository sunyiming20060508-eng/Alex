package com.linli.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 上传校验规则的单元测试。这份规则被本地磁盘和 OSS 两个适配器共用，
 * 所以它一松两个存储就一起松；守住这里等于守住"改名的文本文件不能当图片存进桶"。
 */
class ImagePolicyTest {

    private final ImagePolicy policy = new ImagePolicy(2);

    @Test
    @DisplayName("真 png 字节：认出扩展名并读出宽高")
    void acceptsRealPng() {
        ImagePolicy.Accepted accepted = policy.accept(file("png", "photo.png", bytes("png", 40, 30)), "avatar");

        assertThat(accepted.extension()).isEqualTo("png");
        assertThat(accepted.width()).isEqualTo(40);
        assertThat(accepted.height()).isEqualTo(30);
    }

    @Test
    @DisplayName("jpeg 的两种扩展名都算同一种格式")
    void jpegAndJpgAreTheSameFormat() {
        assertThat(policy.accept(file("jpeg", "a.jpeg", bytes("jpg", 8, 8)), "blog").extension()).isEqualTo("jpg");
        assertThat(policy.accept(file("image/jpeg", "a.jpg", bytes("jpg", 8, 8)), "blog").extension()).isEqualTo("jpg");
    }

    @Test
    @DisplayName("空文件、业务类型不在白名单、超过大小上限都直接拒")
    void rejectsEmptyUnknownBizAndOversize() {
        assertThatThrownBy(() -> policy.accept(file("png", "a.png", new byte[0]), "avatar"))
                .isInstanceOf(ApiException.class).hasMessage("请选择图片");
        assertThatThrownBy(() -> policy.accept(file("png", "a.png", bytes("png", 8, 8)), "goods"))
                .isInstanceOf(ApiException.class).hasMessage("不支持的上传业务类型");
        assertThatThrownBy(() -> policy.accept(file("png", "a.png", new byte[3 * 1024 * 1024]), "avatar"))
                .isInstanceOf(ApiException.class).hasMessage("图片不能超过 2MB");
    }

    @Test
    @DisplayName("声明成 image/png 其实是一段文本：魔数这关过不去")
    void rejectsNonImageBytes() {
        MockMultipartFile fake = file("png", "evil.png", "just text, not an image".getBytes());

        assertThatThrownBy(() -> policy.accept(fake, "avatar"))
                .isInstanceOf(ApiException.class).hasMessage("文件内容不是有效图片");
    }

    @Test
    @DisplayName("真图片但扩展名对不上：防的是改名的上传")
    void rejectsExtensionMismatch() {
        MockMultipartFile renamed = file("png", "photo.txt", bytes("png", 8, 8));

        assertThatThrownBy(() -> policy.accept(renamed, "avatar"))
                .isInstanceOf(ApiException.class).hasMessage("文件扩展名与真实图片格式不一致");
    }

    @Test
    @DisplayName("非 image/* 的声明 MIME 先被挡掉，不用读字节")
    void rejectsNonImageContentType() {
        MockMultipartFile upload = new MockMultipartFile("file", "a.png", "text/plain", bytes("png", 8, 8));

        assertThatThrownBy(() -> policy.accept(upload, "avatar"))
                .isInstanceOf(ApiException.class).hasMessage("只能上传图片");
    }

    @Test
    @DisplayName("对象键是 {biz}/{日期}/{32位uuid}.{ext}，日期分目录、名字不可猜")
    void objectKeyShape() {
        String key = policy.objectKey("avatar", "png");

        assertThat(key).matches("avatar/\\d{4}-\\d{2}-\\d{2}/[0-9a-f]{32}\\.png");
    }

    private static MockMultipartFile file(String contentType, String originalName, byte[] bytes) {
        return new MockMultipartFile("file", originalName, "image/" + contentType, bytes);
    }

    private static byte[] bytes(String format, int width, int height) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
