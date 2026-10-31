package com.linli.common;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 上传的公共规矩：什么样的文件能收、对象键长什么样、宽高怎么读。
 *
 * 本地磁盘和 OSS 两个适配器共用这一份，因为"能不能收"和"存到哪里"是两件事——
 * 规则各写一遍迟早会漂移成本地严、OSS 松（那等于给绕过校验开了个门）。
 *
 * 校验四道：业务白名单、大小、声明的 MIME、真实魔数，外加"扩展名要和魔数一致"。
 * 最后一道是防"把 text/plain 改名成 .png"这种最常见的绕过。
 */
@Component
public class ImagePolicy {

    private static final Set<String> ALLOWED_BIZ = Set.of("common", "avatar", "blog", "shop", "review");

    private final long maxBytes;

    public ImagePolicy(@Value("${linli.upload.max-size-mb:2}") long maxSizeMb) {
        this.maxBytes = maxSizeMb * 1024 * 1024;
    }

    /** 校验通过后的图片：字节、真实扩展名、宽高（读不出来留 null，比如 webp）。 */
    public record Accepted(byte[] bytes, String extension, Integer width, Integer height) {}

    public Accepted accept(MultipartFile file, String biz) {
        if (file == null || file.isEmpty()) throw new ApiException(ErrorCode.PARAM_INVALID, "请选择图片");
        if (!ALLOWED_BIZ.contains(biz)) throw new ApiException(ErrorCode.PARAM_INVALID, "不支持的上传业务类型");
        if (file.getSize() > maxBytes) {
            throw new ApiException(ErrorCode.PARAM_INVALID, "图片不能超过 " + (maxBytes / 1024 / 1024) + "MB");
        }
        String declared = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!declared.startsWith("image/")) throw new ApiException(ErrorCode.PARAM_INVALID, "只能上传图片");

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.SERVER_ERROR, "图片读取失败，请重试");
        }

        String extension = sniff(bytes);
        if (extension == null) throw new ApiException(ErrorCode.PARAM_INVALID, "文件内容不是有效图片");
        if (!extension.equals(suffixOf(file.getOriginalFilename()))) {
            throw new ApiException(ErrorCode.PARAM_INVALID, "文件扩展名与真实图片格式不一致");
        }

        Integer width = null;
        Integer height = null;
        try {
            var image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image != null) {
                width = image.getWidth();
                height = image.getHeight();
            }
        } catch (IOException e) {
            // 宽高只是给前端做占位比例用的，读不出来不该让上传失败
            width = null;
            height = null;
        }
        return new Accepted(bytes, extension, width, height);
    }

    /** 对象键：{biz}/{日期}/{uuid}.{ext}。两个适配器用同一个形状，换存储不改历史数据的读法。 */
    public String objectKey(String biz, String extension) {
        return biz + "/" + LocalDate.now() + "/" + UUID.randomUUID().toString().replace("-", "") + "." + extension;
    }

    /** 按魔数判断真实格式，返回扩展名；认不出返回 null。 */
    private static String sniff(byte[] b) {
        if (b.length >= 8) {
            if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "png";
            if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "jpg";
            if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "gif";
            if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                    && b.length > 11 && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "webp";
        }
        return null;
    }

    /** jpg 和 jpeg 视为同一种格式，避免正常上传被扩展名比对误拒。 */
    private static String suffixOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        String suffix = name.substring(dot + 1).toLowerCase();
        return "jpeg".equals(suffix) ? "jpg" : suffix;
    }
}
