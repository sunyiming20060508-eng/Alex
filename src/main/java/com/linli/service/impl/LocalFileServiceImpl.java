package com.linli.service.impl;

import com.linli.common.ApiException;
import com.linli.common.ErrorCode;
import com.linli.common.ImagePolicy;
import com.linli.common.UploadUrl;
import com.linli.service.FileService;
import com.linli.vo.FileVO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

/**
 * 本地磁盘上传适配器：校验交给 {@link ImagePolicy}，本类只负责落盘和拼地址。
 *
 * 对象键由白名单里的 biz + 当天日期 + UUID 拼成，没有任何用户可控的片段，
 * 所以不需要再做"路径必须落在 root 里"的归一化校验。
 */
public class LocalFileServiceImpl implements FileService {

    private static final Logger log = LoggerFactory.getLogger(LocalFileServiceImpl.class);

    private final ImagePolicy policy;
    private final UploadUrl uploadUrl;
    private final Path root;

    public LocalFileServiceImpl(ImagePolicy policy, UploadUrl uploadUrl, String dir) {
        this.policy = policy;
        this.uploadUrl = uploadUrl;
        this.root = Paths.get(dir).toAbsolutePath().normalize();
    }

    @Override
    public FileVO upload(MultipartFile file, String biz) {
        ImagePolicy.Accepted image = policy.accept(file, biz);
        String objectKey = policy.objectKey(biz, image.extension());
        Path target = root.resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, image.bytes());
        } catch (IOException e) {
            log.error("保存上传图片失败: {}", target, e);
            throw new ApiException(ErrorCode.SERVER_ERROR, "图片保存失败，请重试");
        }
        return new FileVO(uploadUrl.location(objectKey), objectKey, image.width(), image.height());
    }
}
