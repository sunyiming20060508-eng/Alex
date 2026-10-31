package com.linli.common;

/**
 * 同一张图的多种规格地址：现在只有「小圆头像用的缩略图」一种，规则写在服务端而不是客户端，
 * 因为「哪家存储支持什么参数」是存储厂商的知识，放进前端就等于把阿里云 OSS 的语法泄给所有端
 * （将来管理端、小程序都要各自再实现一遍，还会漂）。
 *
 * 拼的是 OSS 的图片处理参数，实测原图 498KB 的头像出 128px 方图是 3.5KB 左右；桶不用额外开通，
 * 图片处理是默认能力，按处理次数计费。
 *
 * 只在「确定能处理」时改写，否则原样返回。原样返回的三种情况都是退化而不是坏掉——图片照样能显示，
 * 只是没变小：
 * 1. 旧数据里存的是单个 emoji 字符（昵称首字兜底那条路径），根本不是地址；
 * 2. local 存储模式下地址是本站路径 /api/file/images/...，本站不支持这个参数；
 * 3. 换成自定义域名或 CDN 前缀后主机不再以 .aliyuncs.com 结尾，猜参数只会拼出 404。
 *
 * 判定只取「scheme + 主机」这一段，因为存进来的地址已经过 {@link OwnImageGuard} 白名单，
 * 这里不再假设有人往 URL 里塞了花样。
 */
public final class ImageVariant {

    private static final String PROCESS_PARAM = "x-oss-process";

    /**
     * 头像圈最大只有 64 CSS px（个人中心那个），128 正好是 2 倍 DPR 的清晰度；
     * m_fill 先裁后缩成正方，前端那个 circle 的 object-fit: cover 才不会把脸挤到边上。
     */
    private static final String AVATAR_PROCESS = "image/resize,m_fill,w_128,h_128/quality,q_80";

    private static final String OSS_HOST_SUFFIX = ".aliyuncs.com";

    private ImageVariant() {}

    /** 头像用的正方形小图地址；不能安全改写时返回传入的原值。 */
    public static String avatarThumb(String url) {
        if (url == null) return null;
        if (!isOssHttpsUrl(url)) return url;
        if (hasProcessParam(url)) return url;
        return url + (url.indexOf('?') < 0 ? "?" : "&") + PROCESS_PARAM + "=" + AVATAR_PROCESS;
    }

    private static boolean isOssHttpsUrl(String url) {
        int schemeEnd = "https://".length();
        if (!url.regionMatches(true, 0, "https://", 0, schemeEnd)) return false;
        int pathStart = url.indexOf('/', schemeEnd);
        String host = pathStart < 0 ? url.substring(schemeEnd) : url.substring(schemeEnd, pathStart);
        int portStart = host.indexOf(':');
        if (portStart >= 0) host = host.substring(0, portStart);
        return host.toLowerCase().endsWith(OSS_HOST_SUFFIX);
    }

    private static boolean hasProcessParam(String url) {
        int queryStart = url.indexOf('?');
        if (queryStart < 0) return false;
        return url.substring(queryStart + 1).startsWith(PROCESS_PARAM + "=")
                || url.contains("&" + PROCESS_PARAM + "=");
    }
}
