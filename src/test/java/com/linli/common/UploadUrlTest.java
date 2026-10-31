package com.linli.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 上传地址簿的单元测试。守的是切换存储后前端拿到的地址还能不能访问、
 * 以及老地址会不会一夜之间变成"非法图片"——这两件事都没有别的地方兜着。
 */
class UploadUrlTest {

    private static final String LOCAL = "/api/file/images";
    private static final String ENDPOINT = "oss-cn-beijing.aliyuncs.com";

    @Test
    @DisplayName("local 模式（默认）用本站前缀")
    void localModeUsesSitePrefix() {
        UploadUrl url = new UploadUrl("local", LOCAL, "", "", "");

        assertThat(url.base()).isEqualTo(LOCAL);
        assertThat(url.location("avatar/2026-10-26/a.png")).isEqualTo(LOCAL + "/avatar/2026-10-26/a.png");
    }

    @Test
    @DisplayName("没配 mode 也按 local 走，本地开发不需要额外开关")
    void blankModeFallsBackToLocal() {
        assertThat(new UploadUrl("", LOCAL, "", "", "").base()).isEqualTo(LOCAL);
    }

    @Test
    @DisplayName("oss 模式拼虚拟主机域名，结尾不带斜杠")
    void ossModeBuildsVirtualHostUrl() {
        UploadUrl url = new UploadUrl("oss", LOCAL, "", "javaaidemon", ENDPOINT);

        assertThat(url.location("blog/2026-10-26/a.png")).isEqualTo("https://javaaidemon." + ENDPOINT + "/blog/2026-10-26/a.png");
    }

    @Test
    @DisplayName("endpoint 手滑写成带协议的也不会拼出双协议")
    void stripsSchemeFromEndpoint() {
        UploadUrl url = new UploadUrl("OSS", LOCAL, "", "bucket", "https://" + ENDPOINT);

        assertThat(url.base()).isEqualTo("https://bucket." + ENDPOINT);
    }

    @Test
    @DisplayName("自定义域名优先于桶域名（挂 CDN 的场景）")
    void customDomainWins() {
        UploadUrl url = new UploadUrl("oss", LOCAL, "https://img.linli.life/", "bucket", ENDPOINT);

        assertThat(url.base()).isEqualTo("https://img.linli.life");
    }

    @Test
    @DisplayName("切到 oss 后，本地前缀仍在白名单里，老数据不会被判非法")
    void keepsLocalPrefixWhenSwitchingToOss() {
        UploadUrl url = new UploadUrl("oss", LOCAL, "", "bucket", ENDPOINT);

        assertThat(url.issuedBases()).containsExactly("https://bucket." + ENDPOINT, LOCAL);
        assertThat(new OwnImageGuard(url).matches(LOCAL + "/avatar/2026-10-25/a.png")).isTrue();
    }

    @Test
    @DisplayName("local 模式下两个前缀重合，白名单不重复登记")
    void dedupesSamePrefix() {
        assertThat(new UploadUrl("local", LOCAL, "", "", "").issuedBases()).containsExactly(LOCAL);
    }

    @Test
    @DisplayName("oss 模式缺桶名或缺 endpoint：启动就失败，不等到上传才 500")
    void ossModeNeedsBucketAndEndpoint() {
        assertThatThrownBy(() -> new UploadUrl("oss", LOCAL, "", "", ENDPOINT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OSS_BUCKET_NAME");
        assertThatThrownBy(() -> new UploadUrl("oss", LOCAL, "", "bucket", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OSS_ENDPOINT");
    }
}
