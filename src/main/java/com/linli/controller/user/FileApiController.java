package com.linli.controller.user;

import com.linli.annotation.RequiresLogin;
import com.linli.common.Result;
import com.linli.service.FileService;
import com.linli.vo.FileVO;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * POST /api/file/upload：登录由类上的 {@link RequiresLogin} 声明，方法里不需要再出现
 * Authorization 头。存储是本地磁盘还是 OSS 由 linli.upload.mode 决定，接口和响应字段不变。
 */
@RestController
@RequestMapping("/api/file")
@RequiresLogin
public class FileApiController {

    private final FileService fileService;

    public FileApiController(FileService fileService) { this.fileService = fileService; }

    @PostMapping("/upload")
    public Result<FileVO> upload(@RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "biz", required = false, defaultValue = "common") String biz) {
        return Result.ok(fileService.upload(file, biz));
    }
}
