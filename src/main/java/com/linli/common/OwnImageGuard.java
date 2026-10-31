package com.linli.common;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * "这个图片地址是不是本平台自己上传的"这条规则的唯一出口。
 *
 * 头像和笔记图片都要判它，规则散在两处迟早会不一样（一处放宽一处收紧）。
 * 白名单是前缀而不是后缀：只认自家上传地址，否则用户可以把内容里塞满外部 URL，
 * 变成图片追踪和内网探测的入口。
 *
 * 前缀集合来自 {@link UploadUrl}，和上传适配器返回给前端的地址同源，所以本地磁盘
 * 换成 OSS 之后白名单自动跟着扩，同时老数据里的本地地址仍然合法。
 */
@Component
public class OwnImageGuard {

    /** 改造前的兼容上传路径，历史数据里还有地址用它，不能一刀切掉。 */
    private static final String LEGACY_IMAGE_PREFIX = "/file/images/";

    private final List<String> prefixes;

    public OwnImageGuard(UploadUrl uploadUrl) {
        this.prefixes = uploadUrl.issuedBases();
    }

    public boolean matches(String url) {
        if (url == null) return false;
        String value = url.trim();
        for (String prefix : prefixes) {
            if (value.startsWith(prefix + "/")) return true;
        }
        return value.startsWith(LEGACY_IMAGE_PREFIX);
    }

    /** 不合法就直接抛 40001，带上是哪个字段的错，省得前端只知道"参数不对"。 */
    public void requireOwn(String url, String field) {
        if (!matches(url)) {
            throw new ApiException(ErrorCode.PARAM_INVALID, field + "只能使用本平台上传的图片地址");
        }
    }

    public void requireOwnAll(List<String> urls, String field) {
        if (urls == null) return;
        urls.forEach(url -> requireOwn(url, field));
    }
}
