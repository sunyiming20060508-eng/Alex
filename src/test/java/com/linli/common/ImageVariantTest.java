package com.linli.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 缩略图地址的派生规则。重点不在"能拼出参数"，而在**什么时候坚决不拼**：
 * 库里存的可能是 emoji、可能是本地磁盘模式下的本站路径、也可能哪天换成自定义域名，
 * 这几种情况拼了参数就是给前端一个 404 的 src。所以每一条"原样返回"都要有名字。
 */
class ImageVariantTest {

    private static final String PROCESS = "?x-oss-process=image/resize,m_fill,w_128,h_128/quality,q_80";
    private static final String OSS_URL = "https://javaaidemon.oss-cn-beijing.aliyuncs.com/avatar/2026-10-26/a.jpg";

    @Test
    @DisplayName("OSS 的 https 地址：拼上正方形小图参数")
    void appendsProcessParam() {
        assertThat(ImageVariant.avatarThumb(OSS_URL)).isEqualTo(OSS_URL + PROCESS);
    }

    @Test
    @DisplayName("已经带过 x-oss-process：不重复拼")
    void doesNotDoubleAppend() {
        assertThat(ImageVariant.avatarThumb(OSS_URL + PROCESS)).isEqualTo(OSS_URL + PROCESS);
        assertThat(ImageVariant.avatarThumb(OSS_URL + "?foo=1&x-oss-process=image/resize,w_100"))
                .isEqualTo(OSS_URL + "?foo=1&x-oss-process=image/resize,w_100");
    }

    @Test
    @DisplayName("已有别的查询参数：用 & 续上而不是把原参数冲掉")
    void keepsExistingQuery() {
        assertThat(ImageVariant.avatarThumb(OSS_URL + "?v=2")).isEqualTo(OSS_URL + "?v=2&" + PROCESS.substring(1));
    }

    @Test
    @DisplayName("emoji、本地路径、null：一律原样返回")
    void leavesNonOssValuesAlone() {
        assertThat(ImageVariant.avatarThumb("🍵")).isEqualTo("🍵");
        assertThat(ImageVariant.avatarThumb("")).isEmpty();
        assertThat(ImageVariant.avatarThumb("/api/file/images/avatar/2026-10-24/a.png"))
                .isEqualTo("/api/file/images/avatar/2026-10-24/a.png");
        assertThat(ImageVariant.avatarThumb(null)).isNull();
    }

    @Test
    @DisplayName("主机名不算数就不拼：路径里藏着 aliyuncs、或拿它当前缀拼域名都不行")
    void requiresTheHostToActuallyBeOss() {
        assertThat(ImageVariant.avatarThumb("https://evil.com/javaaidemon.aliyuncs.com/a.jpg"))
                .isEqualTo("https://evil.com/javaaidemon.aliyuncs.com/a.jpg");
        assertThat(ImageVariant.avatarThumb("https://javaaidemon.aliyuncs.com.attacker.net/a.jpg"))
                .isEqualTo("https://javaaidemon.aliyuncs.com.attacker.net/a.jpg");
    }

    @Test
    @DisplayName("自定义域名和明文 http 不拼：CDN 未必认这个参数")
    void skipsCdnAndPlainHttp() {
        assertThat(ImageVariant.avatarThumb("https://img.linli.example.com/avatar/a.jpg"))
                .isEqualTo("https://img.linli.example.com/avatar/a.jpg");
        assertThat(ImageVariant.avatarThumb("http://javaaidemon.oss-cn-beijing.aliyuncs.com/avatar/a.jpg"))
                .isEqualTo("http://javaaidemon.oss-cn-beijing.aliyuncs.com/avatar/a.jpg");
    }

    @Test
    @DisplayName("带端口这种少见的写法，主机判定取 : 之前那段，缩略图不该悄悄失效")
    void handlesHostWithPort() {
        assertThat(ImageVariant.avatarThumb("https://javaaidemon.oss-cn-beijing.aliyuncs.com:443/avatar/a.jpg"))
                .isEqualTo("https://javaaidemon.oss-cn-beijing.aliyuncs.com:443/avatar/a.jpg" + PROCESS);
    }

    @Test
    @DisplayName(" userinfo 里塞 aliyuncs 不算：@ 之后的那一段才决定能不能拼")
    void ignoresAliyuncsInsideUserInfo() {
        assertThat(ImageVariant.avatarThumb("https://a.aliyuncs.com@evil.com/x.jpg"))
                .isEqualTo("https://a.aliyuncs.com@evil.com/x.jpg");
    }
}
